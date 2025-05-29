package org.drools.lsp.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.drools.completion.DRLCompletionHelper;
import org.drools.drl.parser.antlr4.DRLParserHelper;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;

public class DroolsLspDocumentService implements TextDocumentService {

    private final Map<String, String> sourcesMap = new ConcurrentHashMap<>();

    private final DroolsLspServer server;

    public DroolsLspDocumentService(DroolsLspServer server) {
        this.server = server;

        try {
            initJdtServer();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void initJdtServer() throws Exception {
        // pathToServer = root of the unzipped jdt.ls distro
        String pathToServer = "/home/tkobayas/usr/work/lsp-java-completion/jdt-language-server-1.47.0-202505151856";
        String launcherJar = pathToServer + "/plugins/org.eclipse.equinox.launcher_1.7.0.v20250424-1814.jar";
        String configArea = pathToServer + "/config_linux";  // or config_mac, config_win
        Path workspace = Paths.get("/tmp/jdt-workspace");
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ProcessBuilder pb = new ProcessBuilder(
                "/usr/lib/jvm/java-21-openjdk/bin/java",
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-jar", launcherJar,
                "-configuration", configArea,
                "-data", workspace.toString()
        );
        Process jdtProcess = pb.start();

        InputStream jdtStdOut = jdtProcess.getInputStream();
        OutputStream jdtStdIn  = jdtProcess.getOutputStream();

        Process finalJdtProcess = jdtProcess;
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(finalJdtProcess.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.err.println("[JDT LS] " + line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "jdt-error-logger").start();
        jdtProcess.onExit().thenAccept(proc -> {
            int exitCode = proc.exitValue();
            System.out.println("JDT LS exited with code " + exitCode);
            // clean up, notify your server shutdown logic here…
        });

        LanguageClient client = new LanguageClient() {
            @Override
            public void telemetryEvent(Object object) {
                // no-op
            }
            @Override
            public void publishDiagnostics(PublishDiagnosticsParams params) {
                // no-op
            }
            @Override
            public void showMessage(MessageParams messageParams) {
                // no-op
            }
            @Override
            public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams request) {
                // return a default action immediately
                return CompletableFuture.completedFuture(new MessageActionItem());
            }
            @Override
            public void logMessage(MessageParams message) {
                // no-op
            }
        };

        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                client,
                jdtStdOut,
                jdtStdIn
        );
        launcher.startListening();
        LanguageServer jdtServer = launcher.getRemoteProxy();


        // 5) Initialize
        InitializeParams initParams = new InitializeParams();
        initParams.setRootUri(workspace.toUri().toString());
        // you can set more caps if you want; defaults are fine
        InitializeResult initResult = jdtServer.initialize(initParams).get(10, TimeUnit.SECONDS);
        jdtServer.initialized(new InitializedParams());

        // 6) Create a simple Java file in the workspace
        Path javaFile = workspace.resolve("Test.java");
        String content =
                "public class Test {\n" +
                        "    public void foo() {\n" +
                        "        System.out.prin\n" +
                        "    }\n" +
                        "}\n";
        Files.write(javaFile, content.getBytes());

        // 7) Tell JDT LS about the file
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(javaFile.toUri().toString());
        item.setLanguageId("java");
        item.setVersion(1);
        item.setText(content);
        jdtServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));

        // 8) Give it a moment to index (in a real app you'd listen for diagnostics)
        Thread.sleep(2000);

        // 9) Request completions at the end of "prin"
        Position pos = new Position(2, "        System.out.prin".length());
        TextDocumentIdentifier docId = new TextDocumentIdentifier(item.getUri());
        CompletionParams completionParams = new CompletionParams(docId, pos);
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> completionsFut =
                jdtServer.getTextDocumentService().completion(completionParams);

        // 10) Wait and print
        CompletionList list = completionsFut.thenApply(e ->
                                                               e.isRight() ? e.getRight() : new CompletionList(e.getLeft())
        ).get(5, TimeUnit.SECONDS);

        List<String> stringList = list.getItems().stream()
                .map(ci -> ci.getInsertText())
                .distinct()
                .toList();
        System.out.println("Completions: " + stringList);

        System.out.println("Finish");
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        sourcesMap.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
        CompletableFuture.runAsync(() ->
                server.getClient().publishDiagnostics(
                        new PublishDiagnosticsParams(params.getTextDocument().getUri(), validate())
                )
        );
    }

    private List<Diagnostic> validate() {
        return Collections.emptyList();
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        sourcesMap.put(params.getTextDocument().getUri(), params.getContentChanges().get(0).getText());
        // modify internal state
//        this.documentVersions.put(params.getTextDocument().getUri(), params.getTextDocument().getVersion() + 1);
        // send notification
        CompletableFuture.runAsync(() ->
                server.getClient().publishDiagnostics(
                        new PublishDiagnosticsParams(params.getTextDocument().getUri(), validate())
                )
        );
    }

    public String getRuleName(CompletionParams completionParams) {
        String text = sourcesMap.get(completionParams.getTextDocument().getUri());
        return DRLParserHelper.getFirstRuleName(text);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams completionParams) {
        return CompletableFuture.supplyAsync(() -> Either.forLeft(attempt(() -> getCompletionItems(completionParams))));
    }

    private <T> T attempt(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            server.getClient().showMessage(new MessageParams(MessageType.Error, e.toString()));
        }
        return null;
    }

    public List<CompletionItem> getCompletionItems(CompletionParams completionParams) {
        String text = sourcesMap.get(completionParams.getTextDocument().getUri());

        Position caretPosition = completionParams.getPosition();
        List<CompletionItem> completionItems = DRLCompletionHelper.getCompletionItems(text, caretPosition, server.getClient());

        server.getClient().showMessage(new MessageParams(MessageType.Info, "Position=" + caretPosition));
        server.getClient().showMessage(new MessageParams(MessageType.Info, "completionItems = " + completionItems));

        return completionItems;
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }
}

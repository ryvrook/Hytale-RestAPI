package dev.ryvrook.restapi.console;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/** A command engine for tests. Each line is answered by the behaviour, which gets the sender and the line. */
final class FakeEngine implements CommandRunner.Engine {
    final List<String> ran = new CopyOnWriteArrayList<>();
    final Set<String> known = Set.of("say", "help", "slow", "late", "boom", "many", "stop", "plugin", "echo", "sudo", "op");
    volatile Runnable beforeRun = () -> { };
    volatile BiFunction<CommandSender, String, CompletableFuture<Void>> behaviour = FakeEngine::standard;

    @Override
    public CompletableFuture<Void> run(CommandSender sender, String line) {
        beforeRun.run();
        ran.add(line);
        return behaviour.apply(sender, line);
    }

    @Override
    public List<String> canonical(List<String> words) {
        return DenyListTest.canonical(words);
    }

    @Override
    public boolean known(String firstWord) {
        return known.contains(firstWord.toLowerCase());
    }

    static CompletableFuture<Void> standard(CommandSender s, String line) {
        String[] w = line.split(" ", 2);
        return CompletableFuture.runAsync(() -> {
            switch (w[0]) {
                case "say" -> s.sendMessage(Message.raw("[" + s.getUsername() + "] " + (w.length > 1 ? w[1] : "")));
                case "help" -> s.sendMessage(Message.raw("help line 1\nhelp line 2"));
                case "many" -> {
                    for (int i = 0; i < 20; i++) {
                        s.sendMessage(Message.raw("line " + i));
                    }
                }
                case "boom" -> throw new IllegalStateException("boom");
                case "slow" -> sleep(2_000);
                case "late" -> new Thread(() -> {
                    sleep(100);
                    s.sendMessage(Message.raw("late reply"));
                }).start();
                default -> s.sendMessage(Message.raw("Command not found " + w[0]));
            }
        });
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

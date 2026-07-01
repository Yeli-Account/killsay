package com.example.killsaymod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class KillSayMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("killsaymod");
    private static final Random RANDOM = new Random();
    private static final long TRACK_TIMEOUT = 8000;
    private static final long PENDING_TIMEOUT = 500;
    static KillSayMod INSTANCE;

    List<String> phrases = new ArrayList<>();
    String currentPhraseFile = "killsay.txt";
    final Map<Integer, AttackRecord> tracked = new HashMap<>();
    private final Map<Integer, PendingKill> pendingKills = new HashMap<>();
    boolean enabled = true;
    boolean testing = false;
    private long lastTestTime = 0;
    private int lastSentIndex = -1;
    private boolean wasUsingItem = false;

    static final KeyBinding TOGGLE_KEY = new KeyBinding(
            "key.killsay.toggle",
            InputUtil.Type.KEYSYM,
            -1,
            "category.killsay"
    );
    static int TOGGLE_KEYCODE = -1;

    private static class AttackRecord {
        final long time;
        final String name;
        Vec3d lastPos;
        final World world;
        double lastSeenY;
        float lastHealth;
        boolean seenLowHealth;
        boolean wasInVoid;
        boolean teleported;
        AttackRecord(long time, String name, Vec3d pos, float health, World world) {
            this.time = time;
            this.name = name;
            this.lastPos = pos;
            this.world = world;
            this.lastSeenY = pos.y;
            this.lastHealth = health;
            this.seenLowHealth = false;
            this.wasInVoid = false;
            this.teleported = false;
        }
    }

    private static class PendingKill {
        final long time;
        final String victimName;
        final int entityId;
        final World world;
        final boolean seenLowHealth;
        final boolean wasInVoid;
        PendingKill(long time, String victimName, int entityId, World world, boolean seenLowHealth, boolean wasInVoid) {
            this.time = time;
            this.victimName = victimName;
            this.entityId = entityId;
            this.world = world;
            this.seenLowHealth = seenLowHealth;
            this.wasInVoid = wasInVoid;
        }
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        enabled = loadEnabled();
        saveEnabled(enabled);
        loadPhrases();
        checkReadme();
        TOGGLE_KEYCODE = loadToggleKeycode();
        applyToggleKeycode();
        registerCommands();
        registerAttackTracker();
        registerDeathDetector();
        registerKeybindings();
        LOGGER.info("KillSayMod loaded, enabled={}", enabled);
    }

    Path getPhrasesPath() {
        return getVocabularyDir().resolve("killsay.txt");
    }

    Path getPhrasesPath(String fileName) {
        return getVocabularyDir().resolve(fileName);
    }

    private Path getBaseDir() {
        Path gameDir = MinecraftClient.getInstance().runDirectory.toPath().toAbsolutePath().normalize();
        return gameDir.resolve("killsay");
    }

    private Path getKillsayDir() {
        return getBaseDir();
    }

    Path getVocabularyDir() {
        return getBaseDir().resolve("vocabulary");
    }

    private Path getCurrentFilePath() {
        return getKillsayDir().resolve("current");
    }

    private Path getEnabledFilePath() {
        return getKillsayDir().resolve("enabled");
    }

    private static String readmeContent() {
        return String.join(System.lineSeparator(), defaultReadme());
    }

    private void checkReadme() {
        try {
            Path dir = getKillsayDir();
            Files.createDirectories(dir);

            boolean shouldOpen = false;

            String readmeNew = readmeContent();
            Path readmePath = dir.resolve("README.md");
            if (Files.notExists(readmePath)) {
                Files.writeString(readmePath, readmeNew);
                shouldOpen = true;
                LOGGER.info("Created README.md");
            } else {
                String readmeOld = Files.readString(readmePath);
                if (!readmeOld.equals(readmeNew)) {
                    Files.writeString(readmePath, readmeNew);
                    shouldOpen = true;
                    LOGGER.info("Updated README.md");
                }
            }

            if (shouldOpen) {
                openFile(readmePath.toFile());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to check/open README.md", e);
        }
    }

    private void openFile(java.io.File file) {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("linux")) {
                Runtime.getRuntime().exec(new String[]{"xdg-open", file.getAbsolutePath()});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", file.getAbsolutePath()});
            } else if (os.contains("windows")) {
                Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", file.getAbsolutePath()});
            } else {
                java.awt.Desktop.getDesktop().open(file);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to open file: {}", file, e);
        }
    }

    private void saveCurrentFile(String fileName) {
        try {
            Files.createDirectories(getKillsayDir());
            Files.writeString(getCurrentFilePath(), fileName);
        } catch (IOException e) {
            LOGGER.error("Failed to save current file", e);
        }
    }

    private String loadCurrentFile() {
        try {
            Path path = getCurrentFilePath();
            if (Files.exists(path)) {
                String name = Files.readString(path).trim();
                if (!name.isEmpty()) return name;
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    private Path getToggleKeyFilePath() {
        return getKillsayDir().resolve("togglekey");
    }

    private int loadToggleKeycode() {
        try {
            Path path = getToggleKeyFilePath();
            if (Files.exists(path)) {
                return Integer.parseInt(Files.readString(path).trim());
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    private void saveToggleKeycode(int keyCode) {
        try {
            Files.createDirectories(getKillsayDir());
            Files.writeString(getToggleKeyFilePath(), String.valueOf(keyCode));
        } catch (IOException e) {
            LOGGER.error("Failed to save toggle keycode", e);
        }
    }

    private void applyToggleKeycode() {
        if (TOGGLE_KEYCODE <= 0) {
            TOGGLE_KEY.setBoundKey(InputUtil.UNKNOWN_KEY);
        } else {
            TOGGLE_KEY.setBoundKey(InputUtil.fromKeyCode(TOGGLE_KEYCODE, -1));
        }
        KeyBinding.updateKeysByCode();
    }

    void saveEnabled(boolean state) {
        try {
            Files.createDirectories(getKillsayDir());
            Files.writeString(getEnabledFilePath(), state ? "true" : "false");
        } catch (IOException e) {
            LOGGER.error("Failed to save enabled state", e);
        }
    }

    private boolean loadEnabled() {
        try {
            Path path = getEnabledFilePath();
            if (Files.exists(path)) {
                return "true".equals(Files.readString(path).trim());
            }
        } catch (IOException e) {
            // ignore
        }
        return true;
    }

    private static List<String> defaultReadme() {
        return List.of(
                "# KillSay Mod",
                "",
                "> **\u8be5 Mod \u5b8c\u5168\u514d\u8d39\uff0c\u5982\u679c\u4f60\u662f\u4ed8\u8d39\u83b7\u53d6\u7684\uff0c\u606d\u559c\u4f60\u88ab\u5708\u4e86**",
                "",
                "\u56de\u5bb6\u7684\u8def\uff1ahttps://github.com/Yeli-Account/killsay",
                "",
                "---",
                "",
                "## \u58f0\u660e",
                "",
                "- \u672c Mod \u5b8c\u5168\u514d\u8d39\u5f00\u6e90",
                "- **\u5f00\u53d1\u8005**\uff1a3998055542 / 1106591285",
                "",
                "---",
                "",
                "## \u5360\u4f4d\u7b26",
                "",
                "{name} \u88ab\u51fb\u6740\u8005\u7684\u6e38\u620f\u540d  ",
                "{killer} \u4f60\u81ea\u5df1\u7684\u6e38\u620f\u540d  ",
                "{health} \u4f60\u5f53\u524d\u7684\u751f\u547d\u503c\uff08\u6574\u6570\uff09  ",
                "{item}/{weapon} \u4f60\u624b\u4e2d\u6301\u7684\u7269\u54c1\u540d\u79f0  ",
                "{x} {y} {z} \u4f60\u7684\u5750\u6807  ",
                "{random} \u968f\u673a\u6570 0-999999\uff086\u4f4d\u6570\u5b57\uff09  ",
                "{randomletters} \u968f\u673a\u5b57\u6bcd\u7ec4\u5408\uff08\u56fa\u5b9a6\u4e2a\u5927\u5c0f\u5199\u5b57\u6bcd\uff09  ",
                "",
                "",
                "# \u6307\u4ee4\u8bf4\u660e",
                "",
                "### /ks",
                "\u5f00\u5173\u81ea\u52a8\u558a\u8bdd\u529f\u80fd\u3002\u518d\u6b21\u8f93\u5165\u5207\u6362\u5f00\u5173\u72b6\u6001\u3002",
                "",
                "### /ks refresh",
                "\u91cd\u65b0\u52a0\u8f7d\u5f53\u524d\u8bcd\u6c47\u6587\u4ef6\u3002\u4fee\u6539\u4e86\u8bcd\u6c47\u6587\u4ef6\u540e\u5728\u6e38\u620f\u4e2d\u76f4\u63a5\u91cd\u8f7d\uff0c\u65e0\u9700\u91cd\u542f\u6e38\u620f\u3002",
                "",
                "### /ks reset",
                "\u6062\u590d\u9ed8\u8ba4\u8bcd\u6c47\u3002\u4f1a\u5c06 killsay.txt \u91cd\u7f6e\u4e3a\u521d\u59cb\u5185\u5bb9\u5e76\u5207\u56de\u9ed8\u8ba4\u8bcd\u6c47\u3002",
                "",
                "### /ks test",
                "\u5f00\u5173\u6d4b\u8bd5\u6a21\u5f0f\u3002\u5f00\u542f\u540e\u6bcf 0.5 \u79d2\u81ea\u52a8\u53d1\u9001\u4e00\u6761\u5f53\u524d\u8bcd\u6c47\u4e2d\u7684\u968f\u673a\u6d88\u606f\uff0c\u7528\u4e8e\u6d4b\u8bd5\u5360\u4f4d\u7b26\u6548\u679c\u3002\u518d\u6b21\u8f93\u5165\u5173\u95ed\u3002",
                "",
                "### /ks list",
                "\u5217\u51fa vocabulary/ \u76ee\u5f55\u4e0b\u6240\u6709\u53ef\u7528\u7684 .txt \u8bcd\u6c47\u6587\u4ef6\uff0c\u5f53\u524d\u52a0\u8f7d\u7684\u6587\u4ef6\u524d\u4f1a\u663e\u793a * \u6807\u8bb0\u3002",
                "",
                "### /ks load <\u6587\u4ef6\u540d>",
                "\u52a0\u8f7d\u6307\u5b9a\u7684\u8bcd\u6c47\u6587\u4ef6\uff08\u4e0d\u9700\u8981\u8f93\u5165 .txt \u540e\u7f00\uff0c\u652f\u6301 Tab \u8865\u5168\uff09\u3002\u52a0\u8f7d\u540e\u4f1a\u81ea\u52a8\u4fdd\u5b58\u5f53\u524d\u8bcd\u6c47\uff0c\u4e0b\u6b21\u542f\u52a8\u6e38\u620f\u65f6\u81ea\u52a8\u52a0\u8f7d\u540c\u4e00\u4e2a\u6587\u4ef6\u3002",
                "",
                "### /ks delete <\u6587\u4ef6\u540d>",
                "\u5220\u9664\u6307\u5b9a\u7684\u8bcd\u6c47\u6587\u4ef6\uff08\u4e0d\u80fd\u5220\u9664\u9ed8\u8ba4\u7684 killsay.txt\uff09\u3002",
                "",
                "### /ks add <\u6587\u4ef6\u540d>",
                "\u521b\u5efa\u4e00\u4e2a\u65b0\u7684\u8bcd\u6c47\u6587\u4ef6\uff08\u4e0d\u9700\u8981\u8f93\u5165 .txt \u540e\u7f00\uff09\u3002\u5df2\u5b58\u5728\u7684\u6587\u4ef6\u4e0d\u4f1a\u8986\u76d6\u3002",
                "",
                "### /ks open <\u6587\u4ef6\u540d>",
                "\u7528\u7cfb\u7edf\u9ed8\u8ba4\u7f16\u8f91\u5668\u6253\u5f00\u6307\u5b9a\u7684\u8bcd\u6c47\u6587\u4ef6\uff0c\u652f\u6301 Tab \u8865\u5168\u3002",
                "",
                "---",
                "",
                "## \u914d\u7f6e\u6587\u4ef6",
                "",
                "\u6240\u6709\u8bcd\u6c47\u6587\u4ef6\u5b58\u653e\u5728\u6e38\u620f\u76ee\u5f55\u4e0b\u7684 killsay/vocabulary/ \u6587\u4ef6\u5939\u4e2d\uff1a",
                "",
                ".minecraft/  ",
                "\u2514\u2500\u2500 killsay/  ",
                "    \u251c\u2500\u2500\u2500 README.md       \u2190 \u5e2e\u52a9\u6587\u6863  ",
                "    \u251c\u2500\u2500\u2500 current          \u2190 \u8bb0\u5f55\u5f53\u524d\u8bcd\u6c47\u6587\u4ef6\u540d\uff08\u81ea\u52a8\u7ba1\u7406\uff09  ",
                "    \u251c\u2500\u2500\u2500 enabled          \u2190 \u8bb0\u5f55 /ks \u5f00\u5173\u72b6\u6001\uff08\u81ea\u52a8\u7ba1\u7406\uff09  ",
                "    \u2514\u2500\u2500\u2500 vocabulary/  ",
                "        \u251c\u2500\u2500\u2500 killsay.txt    \u2190 \u9ed8\u8ba4\u8bcd\u6c47\u6587\u4ef6  ",
                "        \u251c\u2500\u2500\u2500 myphrases.txt  \u2190 \u4f60\u53ef\u4ee5\u65b0\u5efa\u81ea\u5df1\u7684\u8bcd\u6c47\u6587\u4ef6  ",
                "        \u2514\u2500\u2500\u2500 ...  ",
                "",
                "\u6587\u4ef6\u683c\u5f0f\uff1a\u6bcf\u884c\u4e00\u6761\u6d88\u606f\uff0c/ks load \u81ea\u52a8\u8fc7\u6ee4\u7a7a\u884c\uff0c\u52a0\u8f7d\u6240\u6709\u975e\u7a7a\u884c\u4f5c\u4e3a\u6d88\u606f\u6c60\u3002",
                "",
                "\u6ce8\u610f\uff1a\u5207\u6362\u8bcd\u6c47\u540e\u91cd\u542f\u6e38\u620f\u4f1a\u81ea\u52a8\u52a0\u8f7d\u4e0a\u6b21\u4f7f\u7528\u7684\u6587\u4ef6\uff0c\u65e0\u9700\u6bcf\u6b21\u624b\u52a8\u5207\u6362\u3002",
                "",
                "---",
                "",
                "## \u6280\u672f\u4fe1\u606f",
                "",
                "- Minecraft \u7248\u672c\uff1a1.21.4",
                "- \u52a0\u8f7d\u5668\uff1aFabric",
                "- \u73af\u5883\uff1a\u5ba2\u6237\u7aef\uff08\u65e0\u9700\u670d\u52a1\u7aef\u5b89\u88c5\uff09",
                "- \u5f00\u6e90\u534f\u8bae\uff1aMIT"
        );
    }

    static List<String> defaultPhrases() {
        return List.of(
                "@{name} 打不过OpenZen，公益Get加群 <{random}...>",
                "@{name} 我正在使用CloudBounce，公益获取加群 <{random}...>",
                "@{name} 公益BMW客户端都打不过你真逆天了，获取加群 <{random}...>",
                "@{name} 一破，卧龙出山，你已被SilenceFix击败！免费获取客户端和同款配置加群 <{random}...>",
                "@{name} 你被我用Naven alpha创死，你已急哭，免费客户端获取加群 <{random}...>",
                "@{name} Ednaven都打不过兄弟你真无敌了公益获取加群 <{random}...>"
        );
    }

    static String getCurrentPhraseFileName() {
        return INSTANCE.currentPhraseFile;
    }

    void loadPhrases() {
        String savedFile = loadCurrentFile();
        if (savedFile != null && !"killsay.txt".equals(savedFile) && loadPhrases(savedFile)) {
            return;
        }
        try {
            Path path = getPhrasesPath();
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, String.join(System.lineSeparator(), defaultPhrases()));
            }
            phrases = Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            currentPhraseFile = "killsay.txt";
            saveCurrentFile("killsay.txt");
            LOGGER.info("Loaded {} phrases from {}", phrases.size(), path);
        } catch (IOException e) {
            LOGGER.error("Failed to load phrases file", e);
            phrases = List.of("1@{name}");
        }
    }

    boolean loadPhrases(String fileName) {
        try {
            Path dir = getVocabularyDir();
            Path path = dir.resolve(fileName);
            if (Files.notExists(path)) {
                if (!fileName.endsWith(".txt")) {
                    path = dir.resolve(fileName + ".txt");
                }
                if (Files.notExists(path)) return false;
            }
            phrases = Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (phrases.isEmpty()) phrases = List.of("@{name}");
            currentPhraseFile = path.getFileName().toString();
            saveCurrentFile(currentPhraseFile);
            LOGGER.info("Loaded {} phrases from {}", phrases.size(), path);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to load phrases file", e);
            return false;
        }
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("ks")
                    .executes(ctx -> {
                        enabled = !enabled;
                        saveEnabled(enabled);
                        String status = enabled ? "\u00a7a\u5f00\u542f" : "\u00a7c\u5173\u95ed";
                        ctx.getSource().sendFeedback(Text.literal("\u00a7e[KillSayMod] \u81ea\u52a8\u8bf4\u8bdd\u5df2" + status));
                        return 1;
                    })
                    .then(literal("refresh")
                            .executes(ctx -> {
                                loadPhrases();
                                ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u8bcd\u6c47\u5df2\u91cd\u65b0\u52a0\u8f7d"));
                                return 1;
                            }))
                    .then(literal("reset")
                            .executes(ctx -> {
                                try {
                                    Path path = getPhrasesPath();
                                    Files.createDirectories(path.getParent());
                                    Files.writeString(path, String.join(System.lineSeparator(), defaultPhrases()));
                                    saveCurrentFile("killsay.txt");
                                    loadPhrases();
                                    ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u6062\u590d\u9ed8\u8ba4\u8bcd\u6c47"));
                                } catch (IOException e) {
                                    ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u6062\u590d\u5931\u8d25: " + e.getMessage()));
                                }
                                return 1;
                            }))
                    .then(literal("test")
                            .executes(ctx -> {
                                testPhrases();
                                String status = testing ? "\u00a7a\u5f00\u542f" : "\u00a7c\u5173\u95ed";
                                ctx.getSource().sendFeedback(Text.literal("\u00a7e[KillSayMod] \u6d4b\u8bd5\u6a21\u5f0f\u5df2" + status));
                                return 1;
                            }))
                    .then(literal("list")
                            .executes(ctx -> {
                                try {
                                    Path dir = getVocabularyDir();
                                    if (Files.notExists(dir)) {
                                        ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u8bcd\u6c47\u76ee\u5f55\u4e0d\u5b58\u5728"));
                                        return 1;
                                    }
                                    List<Path> txtFiles;
                                    try (var stream = Files.list(dir)) {
                                        txtFiles = stream
                                                .filter(p -> p.toString().endsWith(".txt"))
                                                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                                                .toList();
                                    }
                                    if (txtFiles.isEmpty()) {
                                        ctx.getSource().sendFeedback(Text.literal("\u00a7e[KillSayMod] \u00a7f\u6ca1\u6709\u53ef\u7528\u7684\u8bcd\u6c47\u6587\u4ef6"));
                                        return 1;
                                    }
                                    ctx.getSource().sendFeedback(Text.literal("\u00a7e[KillSayMod] \u00a7f\u53ef\u7528\u8bcd\u6c47\u6587\u4ef6\uff1a"));
                                    for (Path p : txtFiles) {
                                        String name = p.getFileName().toString();
                                        String prefix = name.equals(currentPhraseFile) ? " \u00a7a* " : " \u00a77  ";
                                        String suffix = name.equals(currentPhraseFile) ? " \u00a77(\u5f53\u524d)" : "";
                                        ctx.getSource().sendFeedback(Text.literal(prefix + "\u00a7f" + name + suffix));
                                    }
                                } catch (IOException e) {
                                    ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u8bfb\u53d6\u76ee\u5f55\u5931\u8d25: " + e.getMessage()));
                                }
                                return 1;
                            }))
                    .then(literal("load")
                            .then(argument("name", StringArgumentType.greedyString())
                                    .suggests((ctx, builder) -> {
                                        suggestTxtFiles(builder);
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name").trim();
                                        if (loadPhrases(name)) {
                                            ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u52a0\u8f7d\u8bcd\u6c47: " + currentPhraseFile));
                                        } else {
                                            ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u6587\u4ef6\u4e0d\u5b58\u5728: " + name));
                                        }
                                        return 1;
                                    })))
                    .then(literal("delete")
                            .then(argument("name", StringArgumentType.greedyString())
                                    .suggests((ctx, builder) -> {
                                        suggestTxtFiles(builder);
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name").trim();
                                        try {
                                            Path dir = getVocabularyDir();
                                            Path path = dir.resolve(name);
                                            if (Files.notExists(path) && !name.endsWith(".txt")) {
                                                path = dir.resolve(name + ".txt");
                                            }
                                            if (Files.notExists(path)) {
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u6587\u4ef6\u4e0d\u5b58\u5728: " + name));
                                                return 1;
                                            }
                                            String fileName = path.getFileName().toString();
                                            if (fileName.equals("killsay.txt")) {
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u4e0d\u80fd\u5220\u9664\u9ed8\u8ba4\u6587\u4ef6 killsay.txt"));
                                                return 1;
                                            }
                                            Files.delete(path);
                                            if (fileName.equals(currentPhraseFile)) {
                                                loadPhrases();
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u5220\u9664\u5f53\u524d\u8bcd\u6c47\u6587\u4ef6\uff0c\u5df2\u81ea\u52a8\u56de\u9000\u5230\u9ed8\u8ba4\u8bcd\u6c47"));
                                            } else {
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u5220\u9664: " + fileName));
                                            }
                                        } catch (IOException e) {
                                            ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u5220\u9664\u5931\u8d25: " + e.getMessage()));
                                        }
                                        return 1;
                                    })))
                    .then(literal("add")
                            .then(argument("name", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name").trim();
                                        try {
                                            Path dir = getVocabularyDir();
                                            Files.createDirectories(dir);
                                            Path path = dir.resolve(name);
                                            if (!name.endsWith(".txt")) {
                                                path = dir.resolve(name + ".txt");
                                            }
                                            if (Files.exists(path)) {
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u6587\u4ef6\u5df2\u5b58\u5728: " + path.getFileName().toString()));
                                                return 1;
                                            }
                                            Files.writeString(path, "@{name}");
                                            ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u521b\u5efa\u8bcd\u6c47\u6587\u4ef6: " + path.getFileName().toString()));
                                        } catch (IOException e) {
                                            ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u521b\u5efa\u5931\u8d25: " + e.getMessage()));
                                        }
                                        return 1;
                                    })))
                    .then(literal("open")
                            .then(argument("name", StringArgumentType.greedyString())
                                    .suggests((ctx, builder) -> {
                                        suggestTxtFiles(builder);
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name").trim();
                                        try {
                                            Path dir = getVocabularyDir();
                                            Path path = dir.resolve(name);
                                            if (Files.notExists(path)) {
                                                if (!name.endsWith(".txt")) {
                                                    path = dir.resolve(name + ".txt");
                                                }
                                                if (Files.notExists(path)) {
                                                    ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u6587\u4ef6\u4e0d\u5b58\u5728: " + name));
                                                    return 1;
                                                }
                                            }
                                            try {
                                                java.awt.Desktop.getDesktop().open(path.toFile());
                                            } catch (Exception e) {
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u65e0\u6cd5\u6253\u5f00\u6587\u4ef6: " + e.getMessage()));
                                            }
                                        } catch (Exception e) {
                                            ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u9519\u8bef: " + e.getMessage()));
                                        }
                                        return 1;
                                    })))
            );
        });
    }

    private void registerAttackTracker() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!enabled) return ActionResult.PASS;
            if (!world.isClient()) return ActionResult.PASS;
            if (entity instanceof PlayerEntity target) {
                AttackRecord rec = new AttackRecord(
                        System.currentTimeMillis(),
                        getEntityName(target),
                        entity.getPos(),
                        target.getHealth(),
                        world
                );
                if (target.getHealth() <= 4f) rec.seenLowHealth = true;
                tracked.put(entity.getId(), rec);
            }
            return ActionResult.PASS;
        });
    }

    private void registerDeathDetector() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            if (testing && !phrases.isEmpty()) {
                long now = System.currentTimeMillis();
                if (now - lastTestTime >= 500) {
                    lastTestTime = now;
                    String msg = pickPhrase();
                    msg = replacePlaceholders(msg, "\u6d4b\u8bd5\u76ee\u6807", client.player);
                    client.player.networkHandler.sendChatMessage(msg);
                }
            }

            if (!enabled) return;

            if (client.player.isDead() || client.player.getHealth() <= 0f) {
                tracked.clear();
                pendingKills.clear();
                return;
            }

            long now = System.currentTimeMillis();

            wasUsingItem = client.player.isUsingItem();

            checkPendingKills(client, now);

            Iterator<Map.Entry<Integer, AttackRecord>> it = tracked.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, AttackRecord> entry = it.next();
                int entityId = entry.getKey();
                AttackRecord rec = entry.getValue();

                if (now - rec.time > TRACK_TIMEOUT) {
                    it.remove();
                    continue;
                }

                Entity entity = client.world.getEntityById(entityId);

                if (client.player.isDead() || client.player.getHealth() <= 0f) {
                    it.remove();
                    break;
                }

                if (entity instanceof PlayerEntity player) {
                    if (client.world != rec.world) {
                        it.remove();
                        break;
                    }
                    double distToPlayer = Math.sqrt(
                        Math.pow(entity.getX() - client.player.getX(), 2) +
                        Math.pow(entity.getZ() - client.player.getZ(), 2)
                    );
                    if (distToPlayer > 256) {
                        it.remove();
                        continue;
                    }
                    double dx = entity.getX() - rec.lastPos.x;
                    double dz = entity.getZ() - rec.lastPos.z;
                    if (dx * dx + dz * dz > 100) {
                        rec.teleported = true;
                    }
                    rec.lastHealth = player.getHealth();
                    rec.lastPos = entity.getPos();
                    rec.lastSeenY = entity.getY();
                    if (entity.getY() < entity.getWorld().getBottomY()) rec.wasInVoid = true;
                    if (player.getHealth() <= 4f) rec.seenLowHealth = true;
                    if (player.isDead() || player.getHealth() <= 0f) {
                        it.remove();
                        if (now - rec.time < 5000) {
                            trySend(rec.name);
                        }
                        continue;
                    }
                } else if (entity == null) {
                    it.remove();
                    if (client.world != rec.world) break;
                    if (rec.teleported) continue;
                    double dx = rec.lastPos.x - client.player.getX();
                    double dz = rec.lastPos.z - client.player.getZ();
                    if (dx * dx + dz * dz > 1024) continue;
                    pendingKills.put(entityId, new PendingKill(now, rec.name, entityId, rec.world, rec.seenLowHealth, rec.wasInVoid));
                    continue;
                }
            }
        });
    }

    private void registerKeybindings() {
        KeyBindingHelper.registerKeyBinding(TOGGLE_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.wasPressed()) {
                enabled = !enabled;
                saveEnabled(enabled);
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(
                            (enabled ? "\u00a7a" : "\u00a7c") + "KillSay " + (enabled ? "\u5df2\u542f\u7528" : "\u5df2\u5173\u95ed")
                    ), true);
                }
            }
        });
    }

    static void setToggleKeycode(int keyCode) {
        INSTANCE.TOGGLE_KEYCODE = keyCode;
        if (keyCode <= 0) {
            TOGGLE_KEY.setBoundKey(InputUtil.UNKNOWN_KEY);
        } else {
            TOGGLE_KEY.setBoundKey(InputUtil.fromKeyCode(keyCode, -1));
        }
        KeyBinding.updateKeysByCode();
        MinecraftClient.getInstance().options.write();
        INSTANCE.saveToggleKeycode(keyCode);
    }

    private void checkPendingKills(MinecraftClient client, long now) {
        Iterator<Map.Entry<Integer, PendingKill>> it = pendingKills.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PendingKill> entry = it.next();
            PendingKill pk = entry.getValue();

            if (client.world != pk.world) {
                it.remove();
                continue;
            }

            if (now - pk.time > PENDING_TIMEOUT) {
                it.remove();
                if (!entityWithNameExists(client, pk.victimName)) {
                    trySend(pk.victimName);
                }
                continue;
            }
        }
    }

    private boolean entityWithNameExists(MinecraftClient client, String name) {
        if (client.world == null) return false;
        for (Entity e : client.world.getEntities()) {
            if (e instanceof PlayerEntity player && player.isAlive() && e != client.player) {
                String eName = player.getGameProfile().getName();
                if (eName.equals(name)) return true;
            }
        }
        return false;
    }


    public static void onPlayerRemoved(PlayerEntity player) {
        if (INSTANCE == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.isDead() || client.player.getHealth() <= 0f) return;
        if (player == client.player) return;
        if (player.getWorld() == null) return;

        long now = System.currentTimeMillis();

        if (player.getHealth() <= 0f || player.isDead()) {
            AttackRecord rec = INSTANCE.tracked.remove(player.getId());
            if (rec != null) {
                INSTANCE.pendingKills.remove(player.getId());
                if (now - rec.time < 8000) {
                    if (!INSTANCE.entityWithNameExists(client, rec.name)) {
                        INSTANCE.trySend(rec.name);
                    }
                }
            }
        } else {
            AttackRecord rec = INSTANCE.tracked.remove(player.getId());
            if (rec != null) {
                INSTANCE.pendingKills.remove(player.getId());
            }
        }
    }

    private void trySend(String victimName) {
        if (!enabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || phrases.isEmpty()) return;
        if (client.player.isDead() || client.player.getHealth() <= 0f) return;

        String message = pickPhrase();
        message = replacePlaceholders(message, victimName, client.player);

        if (client.isIntegratedServerRunning()) {
            client.inGameHud.getChatHud().addMessage(Text.literal(message));
        } else {
            client.player.networkHandler.sendChatMessage(message);
        }
        LOGGER.info("[KillSay] {} -> {}", victimName, message);
    }

    private String pickPhrase() {
        if (phrases.size() == 1) return phrases.get(0);
        int index;
        do {
            index = RANDOM.nextInt(phrases.size());
        } while (index == lastSentIndex);
        lastSentIndex = index;
        return phrases.get(index);
    }

    void testPhrases() {
        testing = !testing;
        if (testing) {
            lastTestTime = System.currentTimeMillis();
        }
    }

    private void suggestTxtFiles(SuggestionsBuilder builder) {
        try {
            Path dir = getVocabularyDir();
            if (Files.exists(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.toString().endsWith(".txt"))
                            .map(p -> p.getFileName().toString().replace(".txt", ""))
                            .sorted()
                            .forEach(builder::suggest);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String getEntityName(LivingEntity entity) {
        if (entity.hasCustomName()) return entity.getCustomName().getString();
        if (entity instanceof PlayerEntity) return entity.getName().getString();
        return entity.getType().getName().getString();
    }

    private static String replacePlaceholders(String text, String victimName, PlayerEntity player) {
        String name = player.getName().getString();
        String health = String.valueOf((int) Math.ceil(player.getHealth()));

        ItemStack hand = player.getMainHandStack();
        String item = hand.isEmpty() ? "Air" : hand.getItem().getName().getString();

        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder randomLetters = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            randomLetters.append(letters.charAt(RANDOM.nextInt(letters.length())));
        }
        String randomLettersStr = randomLetters.toString();

        String r = text
                .replace("@{name}", victimName)
                .replace("@{killer}", name)
                .replace("{name}", victimName)
                .replace("{killer}", name)
                .replace("{health}", health)
                .replace("{item}", item)
                .replace("{weapon}", item);

        return r
                .replace("{x}", String.valueOf((int) player.getX()))
                .replace("{y}", String.valueOf((int) player.getY()))
                .replace("{z}", String.valueOf((int) player.getZ()))
                .replace("{random}", String.format("%06d", RANDOM.nextInt(1000000)))
                .replace("{randomletters}", randomLettersStr);
    }
}

package com.example.killsaymod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
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
    private static final long TRACK_TIMEOUT = 20000;
    private static final long PENDING_TIMEOUT = 500;
    private static KillSayMod INSTANCE;

    private List<String> phrases = new ArrayList<>();
    private String currentPhraseFile = "killsay.txt";
    private final Map<Integer, AttackRecord> tracked = new HashMap<>();
    private final Map<Integer, PendingKill> pendingKills = new HashMap<>();
    private boolean enabled = true;
    private boolean testing = false;
    private long lastTestTime = 0;
    private long lastCombatTime = 0;
    private int lastSentIndex = -1;
    private boolean wasUsingItem = false;

    private static class AttackRecord {
        final long time;
        final String name;
        Vec3d lastPos;
        final World world;
        double lastSeenY;
        float lastHealth;
        boolean seenLowHealth;
        AttackRecord(long time, String name, Vec3d pos, float health, World world) {
            this.time = time;
            this.name = name;
            this.lastPos = pos;
            this.world = world;
            this.lastSeenY = pos.y;
            this.lastHealth = health;
            this.seenLowHealth = false;
        }
    }

    private static class PendingKill {
        final long time;
        final long attackTime;
        final String victimName;
        final int entityId;
        final World world;
        final boolean seenLowHealth;
        PendingKill(long time, long attackTime, String victimName, int entityId, World world, boolean seenLowHealth) {
            this.time = time;
            this.attackTime = attackTime;
            this.victimName = victimName;
            this.entityId = entityId;
            this.world = world;
            this.seenLowHealth = seenLowHealth;
        }
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        ensureDefaultFile();
        loadAllPhrases();
        createReadme();
        registerCommands();
        registerAttackTracker();
        registerDeathDetector();
        LOGGER.info("KillSayMod loaded");
    }

    private Path getPhrasesPath() {
        Path gameDir = MinecraftClient.getInstance().runDirectory.toPath().toAbsolutePath().normalize();
        Path parent = gameDir.getParent();
        if (parent != null && "versions".equals(parent.getFileName().toString())) {
            return gameDir.resolve("killsay").resolve("killsay.txt");
        }
        return gameDir.resolve("killsay").resolve("killsay.txt");
    }

    private Path getKillsayDir() {
        return getPhrasesPath().getParent();
    }

    private void createReadme() {
        try {
            Path path = getKillsayDir().resolve("README.md");
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, String.join(System.lineSeparator(), defaultReadme()));
                LOGGER.info("Created README.md at {}", path);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create README.md", e);
        }
    }

    private static List<String> defaultReadme() {
        return List.of(
                "# KillSay Mod",
                "",
                "> **\u8be5 Mod \u5b8c\u5168\u514d\u8d39\uff0c\u5982\u679c\u4f60\u662f\u4ed8\u8d39\u83b7\u53d6\u7684\uff0c\u606d\u559c\u4f60\u88ab\u5708\u4e86**",
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
                "{name} \u88ab\u51fb\u6740\u8005\u7684\u6e38\u620f\u540d",
                "{killer} \u4f60\u81ea\u5df1\u7684\u6e38\u620f\u540d",
                "{health} \u4f60\u5f53\u524d\u7684\u751f\u547d\u503c\uff08\u6574\u6570\uff09",
                "{item}/{weapon} \u4f60\u624b\u4e2d\u6301\u7684\u7269\u54c1\u540d\u79f0",
                "{x} {y} {z} \u4f60\u7684\u5750\u6807",
                "{random} \u968f\u673a\u6570 0-999999\uff086\u4f4d\u6570\u5b57\uff09",
                "{randomletters} \u968f\u673a\u5b57\u6bcd\u7ec4\u5408\uff08\u56fa\u5b9a6\u4e2a\u5927\u5c0f\u5199\u5b57\u6bcd\uff09",
                "",
                "---",
                "",
                "## \u6307\u4ee4\u8bf4\u660e",
                "",
                "### /ks",
                "\u5f00\u5173\u51fb\u6740\u81ea\u52a8\u53d1\u8a00\u529f\u80fd\u3002\u518d\u6b21\u8f93\u5165\u5207\u6362\u5f00\u5173\u72b6\u6001\u3002",
                "",
                "### /ksload",
                "\u91cd\u65b0\u52a0\u8f7d killsay/ \u76ee\u5f55\u4e0b\u6240\u6709 .txt \u6587\u4ef6\uff0c\u5408\u5e76\u6240\u6709\u975e\u7a7a\u884c\u4f5c\u4e3a\u8bcd\u6c47\u6c60\u3002\u4fee\u6539\u6587\u4ef6\u540e\u5728\u6e38\u620f\u4e2d\u76f4\u63a5\u8f93\u5165\u5373\u53ef\u5237\u65b0\uff0c\u65e0\u9700\u91cd\u542f\u6e38\u620f\u3002",
                "",
                "### /ksreset",
                "\u6062\u590d\u9ed8\u8ba4\u8bcd\u6c47\u3002\u4f1a\u5c06 killsay.txt \u91cd\u7f6e\u4e3a\u521d\u59cb\u5185\u5bb9\u5e76\u91cd\u65b0\u52a0\u8f7d\u3002",
                "",
                "### /kstest",
                "\u5f00\u5173\u6d4b\u8bd5\u6a21\u5f0f\u3002\u5f00\u542f\u540e\u6bcf 0.5 \u79d2\u81ea\u52a8\u53d1\u9001\u4e00\u6761\u5f53\u524d\u8bcd\u6c47\u4e2d\u7684\u968f\u673a\u6d88\u606f\uff0c\u7528\u4e8e\u6d4b\u8bd5\u5360\u4f4d\u7b26\u6548\u679c\u3002\u518d\u6b21\u8f93\u5165\u5173\u95ed\u3002",
                "",
                "### /kswords list",
                "\u5217\u51fa killsay/ \u76ee\u5f55\u4e0b\u6240\u6709\u53ef\u7528\u7684 .txt \u8bcd\u6c47\u6587\u4ef6\uff0c\u5f53\u524d\u52a0\u8f7d\u7684\u6587\u4ef6\u524d\u4f1a\u663e\u793a * \u6807\u8bb0\u3002",
                "",
                "### /kswords load <\u6587\u4ef6\u540d>",
                "\u52a0\u8f7d\u6307\u5b9a\u7684\u8bcd\u6c47\u6587\u4ef6\uff08\u4e0d\u9700\u8981\u8f93\u5165 .txt \u540e\u7f00\uff0c\u652f\u6301 Tab \u8865\u5168\uff09\u3002",
                "",
                "### /kswords delete <\u6587\u4ef6\u540d>",
                "\u5220\u9664\u6307\u5b9a\u7684\u8bcd\u6c47\u6587\u4ef6\uff08\u4e0d\u80fd\u5220\u9664\u9ed8\u8ba4\u7684 killsay.txt\uff09\u3002",
                "",
                "---",
                "",
                "## \u914d\u7f6e\u6587\u4ef6",
                "",
                "\u6240\u6709\u8bcd\u6c47\u6587\u4ef6\u5b58\u653e\u5728\u6e38\u620f\u76ee\u5f55\u4e0b\u7684 killsay/ \u6587\u4ef6\u5939\u4e2d\uff1a",
                "",
                ".minecraft/",
                "\u2514\u2500\u2500 killsay/",
                "    \u251c\u2500\u2500\u2500 killsay.txt      \u2190 \u9ed8\u8ba4\u8bcd\u6c47\u6587\u4ef6",
                "    \u251c\u2500\u2500\u2500 myphrases.txt    \u2190 \u4f60\u53ef\u4ee5\u65b0\u5efa\u81ea\u5df1\u7684\u8bcd\u6c47\u6587\u4ef6",
                "    \u2514\u2500\u2500\u2500 ...",
                "",
                "\u6587\u4ef6\u683c\u5f0f\uff1a\u6bcf\u884c\u4e00\u6761\u6d88\u606f\uff0c/kswords load \u81ea\u52a8\u8fc7\u6ee4\u7a7a\u884c\uff0c\u52a0\u8f7d\u6240\u6709\u975e\u7a7a\u884c\u4f5c\u4e3a\u6d88\u606f\u6c60\u3002",
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

    private static List<String> defaultPhrases() {
        return List.of(
                "@{name} 打不过Zen，获取zen加群 <{random}...>",
                "@{name} 你被我用Naven alpha打死，你已急哭，公益获取加群 <{random}...>",
                "@{name} 我正在使用CloudBounce，公益获取加群 <{random}...>",
                "@{name} 公益BMW客户端都打不过你真逆天了，获取加群 <{random}...>",
                "@{name} 打不过免费端 --BMWClient <{randomletters}>",
                "@{name} 一破，卧龙出山，你已被SilenceFix击败！免费获取加群 <{random}...>",
                "@{name} Ednaven都打不过兄弟你真无敌了，公益获取加群 <{random}...>"
        );
    }

    private void ensureDefaultFile() {
        try {
            Path path = getPhrasesPath();
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, String.join(System.lineSeparator(), defaultPhrases()));
                LOGGER.info("Created default phrases file at {}", path);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create default phrases file", e);
        }
    }

    private void loadAllPhrases() {
        try {
            Path dir = getKillsayDir();
            if (Files.notExists(dir) || !Files.isDirectory(dir)) {
                phrases = List.of("@{name}");
                return;
            }
            List<String> all = new ArrayList<>();
            List<Path> txtFiles;
            try (var stream = Files.list(dir)) {
                txtFiles = stream
                        .filter(p -> p.toString().endsWith(".txt"))
                        .sorted()
                        .toList();
            }
            for (Path p : txtFiles) {
                List<String> lines = Files.readAllLines(p).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
                all.addAll(lines);
            }
            if (all.isEmpty()) all.add("@{name}");
            phrases = List.copyOf(all);
            currentPhraseFile = "";
            LOGGER.info("Loaded {} phrases from {} files in {}", phrases.size(), txtFiles.size(), dir);
        } catch (IOException e) {
            LOGGER.error("Failed to load all phrases", e);
            if (phrases.isEmpty()) phrases = List.of("@{name}");
        }
    }

    private boolean loadPhrases(String fileName) {
        try {
            Path dir = getKillsayDir();
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
                        String status = enabled ? "\u00a7a\u5f00\u542f" : "\u00a7c\u5173\u95ed";
                        ctx.getSource().sendFeedback(Text.literal("\u00a7e[KillSayMod] \u81ea\u52a8\u8bf4\u8bdd\u5df2" + status));
                        return 1;
                    })
            );
            dispatcher.register(literal("ksload")
                    .executes(ctx -> {
                        loadAllPhrases();
                        ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u5237\u65b0\u52a0\u8f7d\u6240\u6709\u8bcd\u6c47\u6587\u4ef6\uff0c\u5171" + phrases.size() + "\u6761\u8bcd\u6c47"));
                        return 1;
                    })
            );
            dispatcher.register(literal("ksreset")
                    .executes(ctx -> {
                        try {
                            Path path = getPhrasesPath();
                            Files.createDirectories(path.getParent());
                            Files.writeString(path, String.join(System.lineSeparator(), defaultPhrases()));
                            loadAllPhrases();
                            ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u6062\u590d\u9ed8\u8ba4\u8bcd\u6c47"));
                        } catch (IOException e) {
                            ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u6062\u590d\u5931\u8d25: " + e.getMessage()));
                        }
                        return 1;
                    })
            );
            dispatcher.register(literal("kstest")
                    .executes(ctx -> {
                        testPhrases();
                        String status = testing ? "\u00a7a\u5f00\u542f" : "\u00a7c\u5173\u95ed";
                        ctx.getSource().sendFeedback(Text.literal("\u00a7e[KillSayMod] \u6d4b\u8bd5\u6a21\u5f0f\u5df2" + status));
                        return 1;
                    })
            );
            dispatcher.register(literal("kswords")
                    .then(literal("list")
                            .executes(ctx -> {
                                try {
                                    Path dir = getKillsayDir();
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
                                            Path dir = getKillsayDir();
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
                                                loadAllPhrases();
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u5220\u9664\u5f53\u524d\u8bcd\u6c47\u6587\u4ef6\uff0c\u5df2\u81ea\u52a8\u56de\u9000\u5230\u5168\u90e8\u8bcd\u6c47"));
                                            } else {
                                                ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u5df2\u5220\u9664: " + fileName));
                                            }
                                        } catch (IOException e) {
                                            ctx.getSource().sendFeedback(Text.literal("\u00a7c[KillSayMod] \u5220\u9664\u5931\u8d25: " + e.getMessage()));
                                        }
                                        return 1;
                                    })))
            );
        });
    }

    private void registerAttackTracker() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!enabled) return ActionResult.PASS;
            lastCombatTime = System.currentTimeMillis();
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

            if (client.player.handSwinging) {
                lastCombatTime = now;
            }
            if (wasUsingItem && !client.player.isUsingItem()) {
                lastCombatTime = now;
            }
            wasUsingItem = client.player.isUsingItem();

            trackProjectiles(client);

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
                    rec.lastHealth = player.getHealth();
                    rec.lastPos = entity.getPos();
                    rec.lastSeenY = entity.getY();
                    if (player.getHealth() <= 4f) rec.seenLowHealth = true;
                    if (player.isDead() || player.getHealth() <= 0f) {
                        it.remove();
                        if (rec.seenLowHealth || now - rec.time < 500) {
                            trySend(rec.name);
                        }
                        continue;
                    }
                } else if (entity == null) {
                    it.remove();
                    if (client.world != rec.world) break;
                    pendingKills.put(entityId, new PendingKill(now, rec.time, rec.name, entityId, rec.world, rec.seenLowHealth));
                    continue;
                }
            }
        });
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
                boolean diedQuickly = (pk.time - pk.attackTime < 500);
                if (pk.seenLowHealth || diedQuickly) {
                    if (!entityWithNameExists(client, pk.victimName)) {
                        trySend(pk.victimName);
                    }
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

    private void trackProjectiles(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCombatTime > 2000) return;
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ProjectileEntity projectile)) continue;
            if (projectile.getOwner() != client.player) continue;
            if (tracked.containsKey(entity.getId())) continue;
            for (Entity target : client.world.getOtherEntities(
                    client.player, entity.getBoundingBox().expand(0.1))) {
                if (!(target instanceof PlayerEntity player)) continue;
                if (tracked.containsKey(target.getId()) || !player.isAlive()) continue;
                if (entity instanceof PersistentProjectileEntity pa) {
                    Vec3d vel = pa.getVelocity();
                    if (vel.lengthSquared() < 0.01
                            && !entity.getBoundingBox().intersects(
                                    target.getBoundingBox().expand(0.1))) {
                        continue;
                    }
                }
                tracked.put(target.getId(), new AttackRecord(
                        now,
                        getEntityName(player),
                        target.getPos(),
                        player.getHealth(),
                        client.world
                ));
            }
        }
    }

    public static void onPlayerRemoved(PlayerEntity player) {
        if (INSTANCE == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.isDead() || client.player.getHealth() <= 0f) return;
        if (player == client.player) return;
        if (player.getWorld() == null) return;

        long now = System.currentTimeMillis();

        if (player.isDead() || player.getY() <= player.getWorld().getBottomY()) {
            AttackRecord rec = INSTANCE.tracked.remove(player.getId());
            if (rec != null) {
                if (rec.seenLowHealth || now - rec.time < 500) {
                    INSTANCE.pendingKills.remove(player.getId());
                    if (!INSTANCE.entityWithNameExists(client, rec.name)) {
                        INSTANCE.trySend(rec.name);
                    }
                }
            }
        }
    }

    private void trySend(String victimName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || phrases.isEmpty()) return;
        if (client.player.isDead() || client.player.getHealth() <= 0f) return;

        String message = pickPhrase();
        message = replacePlaceholders(message, victimName, client.player);

        client.player.networkHandler.sendChatMessage(message);
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

    private void testPhrases() {
        testing = !testing;
        if (testing) {
            lastTestTime = System.currentTimeMillis();
        }
    }

    private void suggestTxtFiles(SuggestionsBuilder builder) {
        try {
            Path dir = getKillsayDir();
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

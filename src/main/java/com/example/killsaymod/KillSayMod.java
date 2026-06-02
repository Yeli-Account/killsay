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
    private static final long KILL_COOLDOWN = 500;
    private static final long PENDING_TIMEOUT = 3000;

    private List<String> phrases = new ArrayList<>();
    private String currentPhraseFile = "killsay.txt";
    private final Map<Integer, AttackRecord> tracked = new HashMap<>();
    private final Map<Integer, PendingKill> pendingKills = new HashMap<>();
    private final Map<Integer, Float> entityHealthMap = new HashMap<>();
    private long lastKillTime = 0;
    private boolean enabled = true;
    private boolean testing = false;
    private long lastTestTime = 0;
    private long lastCombatTime = 0;

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
        final String victimName;
        final int entityId;
        final World world;
        PendingKill(long time, String victimName, int entityId, World world) {
            this.time = time;
            this.victimName = victimName;
            this.entityId = entityId;
            this.world = world;
        }
    }

    @Override
    public void onInitializeClient() {
        loadPhrases();
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
                "> **该 Mod 完全免费，如果你是付费获取的，恭喜你被圈了**",
                "",
                "一个 Minecraft Fabric \u5ba2\u6237\u7aef\u6a21\u7ec4\uff0c\u51fb\u6740\u81ea\u52a8\u53d1\u8a00\u3002",
                "",
                "---",
                "",
                "## \u58f0\u660e",
                "",
                "- \u672c Mod \u5b8c\u5168\u514d\u8d39\u5f00\u6e90",
                "- **\u5f00\u53d1\u8005**\uff1a3998055542 / 1106591285",
                "- \u5982\u679c\u4f60\u662f\u901a\u8fc7\u4ed8\u8d39\u83b7\u5f97\u7684\u6b64 Mod\uff0c\u8bf7\u7acb\u5373\u8054\u7cfb\u5f00\u53d1\u8005\u4e3e\u62a5",
                "",
                "---",
                "",
                "## \u5360\u4f4d\u7b26",
                "",
                "| \u5360\u4f4d\u7b26 | \u652f\u6301 @ \u524d\u7f00 | \u8bf4\u660e |",
                "|--------|:----------:|------|",
                "| `{name}` | `@{name}` | \u88ab\u51fb\u6740\u8005\u7684\u6e38\u620f\u540d |",
                "| `{killer}` | `@{killer}` | \u4f60\u81ea\u5df1\u7684\u6e38\u620f\u540d |",
                "| `{health}` | \u274c | \u4f60\u5f53\u524d\u7684\u751f\u547d\u503c\uff08\u6574\u6570\uff09 |",
                "| `{item}` / `{weapon}` | \u274c | \u4f60\u624b\u4e2d\u6301\u7684\u7269\u54c1\u540d\u79f0 |",
                "| `{x}` `{y}` `{z}` | \u274c | \u4f60\u7684\u5750\u6807 |",
                "| `{random}` | \u274c | \u968f\u673a\u6570 0-999999\uff086\u4f4d\u6570\u5b57\uff09 |",
                "| `{randomletters}` | \u274c | \u968f\u673a\u5b57\u6bcd\u7ec4\u5408\uff08\u56fa\u5b9a6\u4e2a\u5927\u5c0f\u5199\u5b57\u6bcd\uff09 |",
                "",
                "---",
                "",
                "## \u6307\u4ee4\u8bf4\u660e",
                "",
                "### /ks",
                "\u5f00\u5173\u81ea\u52a8\u558a\u8bdd\u529f\u80fd\u3002\u518d\u6b21\u8f93\u5165\u5207\u6362\u5f00\u5173\u72b6\u6001\u3002",
                "",
                "### /ksreload",
                "\u91cd\u65b0\u52a0\u8f7d\u5f53\u524d\u8bcd\u6c47\u6587\u4ef6\u3002\u4fee\u6539\u4e86 killsay.txt \u540e\u5728\u6e38\u620f\u4e2d\u76f4\u63a5\u91cd\u8f7d\uff0c\u65e0\u9700\u91cd\u542f\u6e38\u620f\u3002",
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
                "@{name} Ez\uff0c\u6253\u4e0d\u8fc7OpenZen\uff0cGet \u4e0a Zen.<{randomletters}...>",
                "@{name} \u6ca1\u6253\u8fc7CloudBounce\u5341\u5206\u6401\u7b11\uff0c\u516c\u76ca\u83b7\u53d6\u52a0\u7fa4 <{random}...>",
                "@{name} \u516c\u76caBMW\u5ba2\u6237\u7aef\u90fd\u6253\u4e0d\u8fc7\u4f60\u771f\u9006\u5929\u4e86\uff0c\u83b7\u53d6\u52a0\u7fa4 <{random}...>",
                "@{name} \u4e00\u7834\uff0c\u5367\u9f99\u51fa\u5c71\uff0c\u4f60\u5df2\u88abSilenceFix\u51fb\u8d25\uff01\u514d\u8d39\u83b7\u53d6\u5ba2\u6237\u7aef\u548c\u540c\u6b3e\u914d\u7f6e\u52a0\u7fa4 <{random}...> \u6b23\u6b23\u54e5\u4fdd\u8bc1\u4e0d\u6536\u53d6\u4e00\u5206\u94b1\u54e6\uff01",
                "@{name} Naven alpha\u725bb\uff0c\u514d\u8d39\u5ba2\u6237\u7aef\u83b7\u53d6\u52a0\u7fa4 <{random}...>",
                "@{name} Ednaven\u90fd\u6253\u4e0d\u8fc7\u5144\u5f1f\u4f60\u771f\u65e0\u654c\u4e86\u516c\u76ca\u83b7\u53d6\u52a0\u7fa4 <{random}...>"
        );
    }

    private void loadPhrases() {
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
            LOGGER.info("Loaded {} phrases from {}", phrases.size(), path);
        } catch (IOException e) {
            LOGGER.error("Failed to load phrases file", e);
            phrases = List.of("1@{name}");
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
            dispatcher.register(literal("ksreload")
                    .executes(ctx -> {
                        loadPhrases();
                        ctx.getSource().sendFeedback(Text.literal("\u00a7a[KillSayMod] \u8bcd\u6c47\u5df2\u91cd\u65b0\u52a0\u8f7d"));
                        return 1;
                    })
            );
            dispatcher.register(literal("ksreset")
                    .executes(ctx -> {
                        try {
                            Path path = getPhrasesPath();
                            Files.createDirectories(path.getParent());
                            Files.writeString(path, String.join(System.lineSeparator(), defaultPhrases()));
                            loadPhrases();
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
            );
        });
    }

    private void registerAttackTracker() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!enabled) return ActionResult.PASS;
            lastCombatTime = System.currentTimeMillis();
            if (entity instanceof LivingEntity living) {
                tracked.put(entity.getId(), new AttackRecord(
                        System.currentTimeMillis(),
                        getEntityName(living),
                        entity.getPos(),
                        living.getHealth(),
                        world
                ));
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
                    String msg = phrases.get(RANDOM.nextInt(phrases.size()));
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

            if (client.player.handSwinging || client.player.isUsingItem()) {
                lastCombatTime = now;
            }

            trackEntityHealth(client);

            checkPendingKills(client, now);

            if (now - lastKillTime < KILL_COOLDOWN) return;

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

                if (entity instanceof LivingEntity living) {
                    if (client.world != rec.world) {
                        it.remove();
                        break;
                    }
                    rec.lastHealth = living.getHealth();
                    rec.lastPos = entity.getPos();
                    rec.lastSeenY = entity.getY();
                    if (living.getHealth() <= 4f) rec.seenLowHealth = true;
                    if (living.isDead() || living.getHealth() <= 0f) {
                        it.remove();
                        trySend(rec.name);
                        lastKillTime = now;
                        break;
                    }
                } else if (entity == null) {
                    it.remove();
                    if (client.world != rec.world) break;
                    boolean fellToVoid = rec.lastSeenY < client.player.getY() - 20 || rec.lastSeenY < -16;
                    boolean nearLastPos = client.player.squaredDistanceTo(rec.lastPos) < 48 * 48;
                    boolean diedQuickly = (now - rec.time < 500);
                    if (fellToVoid) {
                        pendingKills.put(entityId, new PendingKill(now, rec.name, entityId, rec.world));
                    } else if (nearLastPos && diedQuickly) {
                        trySend(rec.name);
                        lastKillTime = now;
                    }
                    break;
                }
            }
        });
    }

    private void checkPendingKills(MinecraftClient client, long now) {
        Iterator<Map.Entry<Integer, PendingKill>> it = pendingKills.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PendingKill> entry = it.next();
            int entityId = entry.getKey();
            PendingKill pk = entry.getValue();

            if (client.world != pk.world) {
                it.remove();
                continue;
            }

            if (now - pk.time > PENDING_TIMEOUT) {
                it.remove();
                trySend(pk.victimName);
                lastKillTime = now;
                break;
            }

            Entity entity = client.world.getEntityById(entityId);
            if (entity instanceof LivingEntity living && !living.isDead() && living.getHealth() > 0f) {
                it.remove();
            }
        }
    }

    private void trackEntityHealth(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastCombatTime > 2000) return;
        double range = 64.0;
        Map<Integer, Float> newHealthMap = new HashMap<>();
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (entity == client.player) continue;
            if (client.player.squaredDistanceTo(entity) > range * range) continue;
            int id = entity.getId();
            float hp = living.getHealth();
            if (hp <= 0f || living.isDead()) continue;
            Float prev = entityHealthMap.get(id);
            if (prev != null && hp < prev && !tracked.containsKey(id)) {
                tracked.put(id, new AttackRecord(
                        System.currentTimeMillis(),
                        getEntityName(living),
                        entity.getPos(),
                        hp,
                        client.world
                ));
            }
            newHealthMap.put(id, hp);
        }
        entityHealthMap.clear();
        entityHealthMap.putAll(newHealthMap);
    }

    private void trySend(String victimName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || phrases.isEmpty()) return;

        String message = phrases.get(RANDOM.nextInt(phrases.size()));
        message = replacePlaceholders(message, victimName, client.player);

        client.player.networkHandler.sendChatMessage(message);
        LOGGER.info("[KillSay] {} -> {}", victimName, message);
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

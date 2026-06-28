# KillSay Mod

> 击杀玩家后自动发送自定义消息的 Fabric 客户端模组 (1.21.4)

---

## 修复记录 (2026-06-28)

### 删除
- 移除 `/ks gui` 指令及对应 GUI 界面 (`KillSayConfigScreen.java`)
- 移除 `OPEN_GUI_KEY` 按键绑定 (P 键)
- 移除运行时自动生成 CHANGELOG 的逻辑
- 移除项目目录中多余文件 (zip 包、Zone.Identifier 残留等)

### Bug 修复

1. **走出渲染范围误触发嘲讽**
   - 根因：tick 循环中 `entity == null` 分支无条件创建 `PendingKill`，500ms 后 `entityWithNameExists` 因实体不在当前世界（超出渲染范围）返回 false，导致误发。
   - 修复：`entity == null` 分支仅当 `seenLowHealth || wasInVoid` 时才创建 `PendingKill`，满血正常离开渲染范围不再触发。

2. **虚空击杀有概率不触发**
   - 根因：`entity == null` 分支中 `distToPlayer > 128` 检查，玩家将敌人击落虚空后若自身移动超过 128 格则跳过不处理。
   - 修复：移除距离检查，虚空击杀不再受玩家移动距离影响。

### 潜在问题修复
- `AttackEntityCallback` 增加 `world.isClient()` 检查，防止服务端误触发
- 清理 `lastCombatTime` 死代码（只写不读）
- 删除未使用的 `GLFW`、`ClientPlayConnectionEvents` import
- 从 `fabric.mod.json` 移除不存在的 `icon` 引用

---

## 占位符

`{name}` — 被击杀者游戏名
`{killer}` — 击杀者游戏名
`{health}` — 击杀者当前生命值（整数）
`{item}/{weapon}` — 手持物品名称
`{x} {y} {z}` — 击杀者坐标
`{random}` — 6位随机数字
`{randomletters}` — 6位随机大小写字母

---

## 指令

| 指令 | 说明 |
|------|------|
| `/ks` | 开关自动喊话 |
| `/ks refresh` | 重新加载当前词汇 |
| `/ks reset` | 恢复默认词汇 |
| `/ks test` | 开关测试模式 |
| `/ks list` | 列出可用词汇文件 |
| `/ks load <文件名>` | 加载指定词汇 |
| `/ks delete <文件名>` | 删除词汇文件 |
| `/ks add <文件名>` | 创建词汇文件 |
| `/ks open <文件名>` | 系统编辑器打开词汇文件 |

---

## 技术信息

- Minecraft 1.21.4 / Fabric / 客户端（无需服务端）


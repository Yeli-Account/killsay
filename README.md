# KillSay Mod

---

## 占位符

`{name}` / `@{name}` — 被击杀者游戏名
`{killer}` / `@{killer}` — 击杀者游戏名
`{health}` — 击杀者当前生命值（整数）
`{item}` / `{weapon}` — 击杀者手持物品名称
`{x}` `{y}` `{z}` — 击杀者坐标
`{random}` — 6 位随机数字
`{randomletters}` — 6 位随机大小写字母

---

## 指令

`/ks` — 开关自动喊话
`/ks refresh` — 重新加载当前词汇文件
`/ks reset` — 恢复默认词汇
`/ks test` — 开关测试模式（每 0.5 秒发一条）
`/ks list` — 列出所有可用词汇文件
`/ks load <文件名>` — 加载指定词汇文件
`/ks delete <文件名>` — 删除词汇文件
`/ks add <文件名>` — 创建新词汇文件
`/ks open <文件名>` — 用系统编辑器打开词汇文件

---

## 配置文件

首次启动会自动在游戏目录下创建以下结构：

```
.minecraft/
└── killsay/
    ├── README.md                  帮助文档
    ├── current                   当前词汇文件名（自动管理）
    ├── enabled                   开关状态（自动管理）
    ├── togglekey                 按键绑定键码
    └── vocabulary/
        ├── killsay.txt           默认词汇文件
        ├── myphrases.txt         你可以新建自己的词汇文件
        └── ...
```

文件格式：每行一条消息，空行自动过滤。

示例 `killsay.txt`：

```
@{name} 打不过OpenZen，公益Get加群 <{random}...>
@{name} 我正在使用CloudBounce，公益获取加群 <{random}...>
@{name} 公益BMW客户端都打不过你真逆天了，获取加群 <{random}...>
```

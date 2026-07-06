# InvSee

查看其他玩家背包和末影箱的插件，支持在线和离线玩家。附带常用随身功能指令。

**支持平台**: Luminol / Folia / Paper 1.21.x

## 功能

### 查看背包 / 末影箱

- `/invsee <玩家>` — 查看在线/离线玩家的背包（含装备栏、副手）
- `/endersee <玩家>` — 查看在线/离线玩家的末影箱

**共享引用架构**（和原版箱子共享机制一样）：
- 查看者打开目标的背包/末影箱时，直接读写目标的物品槽位
- 不需要定时同步、不需要写回逻辑
- 修改即时生效，不会卡没、不会复制

### 随身功能

| 指令 | 别名 | 说明 |
|------|------|------|
| `/enderchest` | `/ec` | 打开自己的末影箱 |
| `/workbench` | `/wb`, `/craft` | 打开工作台 |
| `/anvil` | | 打开铁砧 |
| `/grindstone` | | 打开砂轮 |
| `/enchanttable` | `/et` | 远程打开绑定的附魔台 |
| `/et set` | | 绑定面前的附魔台（需摆好15个书架=30级） |
| `/stonecutter` | `/sc` | 打开切石机 |
| `/cartography` | `/ct` | 打开制图台 |
| `/loom` | | 打开织布机 |

### 离线玩家

- 离线背包从 `.dat` NBT 文件读取，关闭时写回
- 目标玩家上线时自动关闭管理员的离线视图，避免冲突
- 关闭时如果目标已在线，直接应用到在线背包

### 权限

```yaml
# 查看他人
invsee.view:       # /invsee    (默认 OP)
invsee.endersee:   # /endersee  (默认 OP)
invsee.modify:     # 修改他人背包/末影箱 (默认 OP)

# 随身功能
invsee.enderchest:     # /ec   (默认 OP)
invsee.workbench:      # /wb   (默认 OP)
invsee.anvil:          # /anvil (默认 OP)
invsee.grindstone:     # /grindstone (默认 OP)
invsee.enchanttable:   # /et   (默认 OP)
invsee.stonecutter:    # /sc   (默认 OP)
invsee.cartography:    # /ct   (默认 OP)
invsee.loom:           # /loom (默认 OP)
```

## 构建

```bash
javac -cp "<luminol-api-jar>:<adventure-api-jar>:<adventure-key-jar>:<examination-api-jar>:<bungeecord-chat-jar>" \
  -d target/classes src/main/java/com/example/invsee/InvSee.java
cp src/main/resources/plugin.yml target/classes/
cd target/classes && jar cfM ../../InvSee-1.0.0.jar plugin.yml com
```

## 技术架构

```
背包 (/invsee)               末影箱 (/endersee)
─────────────               ────────────────
Proxy(Container) 45格       Proxy(Container) 27格
  ├─ 槽0-40 → 目标          ├─ 槽0-26 → 目标
  │   PlayerInventory       │   EnderChestInventory
  ├─ 槽41-44 → 玻璃         │
  │                         │
  └─ setItem → 调度到       └─ setItem → 调度到
      目标线程执行               目标线程执行
```

读写操作用 Java `Proxy` 动态代理 `net.minecraft.world.Container` 接口，实现共享引用。
写入操作通过 `target.getScheduler().run()` 调度到目标玩家的区域线程，Folia 兼容。

## 许可证

WTFPL

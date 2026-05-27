# InvSee

查看其他玩家背包和末影箱的插件，支持在线和离线玩家。

**支持平台**: Luminol / Folia / Paper 1.21.x

## 功能

- `/invsee <玩家>` - 查看玩家背包（含装备栏和副手）
- `/endersee <玩家>` - 查看玩家末影箱
- 支持在线和离线玩家
- 修改权限控制 (`invsee.modify`)

## 指令

| 指令 | 别名 | 说明 | 权限 |
|------|------|------|------|
| `/invsee <玩家>` | `/invs` | 查看背包 | `invsee.view` |
| `/endersee <玩家>` | `/ecsee` | 查看末影箱 | `invsee.endersee` |

## 权限

```yaml
invsee.view:       # 查看他人背包 (默认 OP)
invsee.endersee:   # 查看他人末影箱 (默认 OP)
invsee.modify:     # 修改他人背包/末影箱 (默认 OP)
```

## 构建

```bash
mvn clean package
```

或手动编译：

```bash
javac -cp "<paper-api-jar>" -d target/classes src/main/java/com/example/invsee/InvSee.java
cp src/main/resources/plugin.yml target/classes/
jar cfM InvSee.jar -C target/classes .
```

## 许可证

WTFPL

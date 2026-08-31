# cet36 主题资源（已从 cet36_vue 同步 PNG）

路径与 cet36_vue `src/assets/` 对齐：

| 文件 | 用途 |
|------|------|
| `img/nav.png` | 顶栏背景（`theme.scss` `--theme-header-bg-image`） |
| `img/home/home-bg.png` | 首页底图（cet36 首页专用；业务页为纯色 `#17182c`） |
| `img/home/home-item-bg1.png` | 可点击功能块 hover/active（`.cet36-tab-card`） |
| `img/home/home-item-bg.png` | 区块 hover 态（可选） |
| `img/home/label1.png` … `label5.png` | 区块标题图标（`ThemePanel`） |
| `img2/wg.png` | 面板纹理（`.cet36-panel`，与 cet36 `common.scss` 一致） |
| `img2/sn-title.png` | 无 label 图标时的标题前缀装饰 |

更新资源：从 `cet36_vue/src/assets/` 复制同名文件覆盖即可。

同步命令示例（PowerShell）：

```powershell
$src = "D:\Documents\Code\Java\cet36_vue\src\assets"
$dst = "D:\Documents\Code\Java\pdwfx\frontend\src\assets\cet36"
Copy-Item "$src\img\nav.png" "$dst\img\" -Force
Copy-Item "$src\img\home\*.png" "$dst\img\home\" -Force
Copy-Item "$src\img2\wg.png" "$dst\img2\" -Force
Copy-Item "$src\img2\sn-title.png" "$dst\img2\" -Force
```

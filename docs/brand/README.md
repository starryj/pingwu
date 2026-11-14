# 凭物 / Memento 品牌资源

本目录放 App 直接使用的品牌图与源图。**改图请改源脚本，不要手改 PNG。**

## 目录

| 路径 | 用途 |
| --- | --- |
| `ic_launcher-play-512.png` | 上架用 512×512 方图（Google Play / 各商店） |
| `splash-dark.png` / `splash-paper.png` | 开屏封面原图 1080×2160（深墨版 / 纸白版），与 `res/drawable-nodpi/splash_*.webp` 同源 |
| `source/app-icon-*.png` | 1024×1024 App 图标三个配色（纸白底墨棕 / 墨棕底白 / 透明底墨棕） |
| `source/lockup-*.png` | 横版 lockup：猫箱 + 书法「凭物」+ MEMENTO |
| `source/logo-stacked-*.png` | 竖排版（图形 / 中文 / 英文三行居中对齐） |
| `_legacy/` | 换品牌前的旧资源备份（青绿药丸图标、旧 manifest、旧主题），仅供回滚参考 |

## 颜色（以 `res/values/pw_brand.xml` 为准）

| 名称 | 值 | 用在哪 |
| --- | --- | --- |
| 墨棕 `pw_brand_brown` | `#9E4D2F` | 纸白底上的图形与文字 |
| 纸白 `pw_icon_bg` / `pw_paper` | `#F5F0E3` | 图标底色、开屏纸白版底色 |
| 深墨 `pw_ink` | `#0B1216` | 深墨版开屏底色、`windowBackground` |
| 琥珀（深色强调） | `#F5BE6B` | 深墨底上的细线与强调，对应 `DarkPalette.amber` |

## 字体与授权

- 中文书法「凭物」是 AI 生成的图形，不依赖任何字库。
- 英文 `MEMENTO` 用 **Poppins 500**（SIL OFL 1.1，商用免费；源文件 `pingwu-logo/fonts/poppins`）。
  早期版本用的 Century Gothic 属 Monotype 商业字体，已弃用，不要再回退。
- 中文小字（开屏两行说明）用系统 `NotoSerifSC-VF`（OFL），由 Pillow 排版渲染进图片，App 内不含字体文件。
- 商标（第 9 / 42 类）尚未检索，发布前需自查。

## 资源是怎么生成的

源工程在 `.\01-WorkArea\99-Others\pingwu-logo`：

```powershell
$py='C:\Users\user\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
cd .\01-WorkArea\99-Others\pingwu-logo
& $py compose_splash.py        # → exports/splash/splash-{dark,paper}.png
& $py build_android_assets.py  # → exports/android/res/**（图标各档 + splash webp + 512 方图）
```

再把 `exports/android/res` 下的文件同步进 `app/src/main/res`（robocopy，exit code 3 属正常）。

## App 内引用关系

| 资源 | 被谁引用 |
| --- | --- |
| `drawable-nodpi/ic_launcher_foreground_pw.png` + `values/pw_brand.xml` 的 `pw_icon_bg` | `mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml` 的自适应图标 |
| `drawable-nodpi/ic_launcher_monochrome_pw.png` | 同上（Android 13 主题图标，系统按壁纸染色） |
| `mipmap-*/ic_launcher(.round).png` | Android 12 及以下的传统图标 |
| `drawable-nodpi/splash_dark.webp` / `splash_paper.webp` | `ui/SplashCover.kt` 的 `PingWuSplash()` |
| `values/themes.xml`、`values-v31/themes.xml` | `AndroidManifest.xml` 的 `android:theme="@style/Theme.PingWu"` |

完整改动清单与验证状态见 `docs/交接-20261114-品牌图与开屏封面.md`。

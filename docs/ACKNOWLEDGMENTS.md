# 致谢与项目来源

## 原作者与上游项目

**澜音（LyricWave）** 基于 **[Zennmn（Zen）的 AM++](https://github.com/Zennmn/AM-plus-plus)** 开展二次开发，由 **[Laoch832](https://github.com/Laoch832)** 维护本分支。感谢原作者和上游贡献者建立的 Apple Music 增强模块、宿主适配、歌词处理、播放器布局与设置基础。

澜音由 Laoch832 维护独立的应用名称、文档、构建与版本发布。上游图标、历史界面素材和第三方来源继续明确署名。

本分支继续完善 AI 歌词翻译缓存、原歌词更新校验、异常重试，以及云端测试、固定签名与个人账号发布流程。上游已有功能与贡献不归为本分支原创；完整贡献历史保留在 Git 提交记录中。

- 原项目与作者：[Zennmn/AM-plus-plus](https://github.com/Zennmn/AM-plus-plus)
- 上游贡献记录：[Contributors](https://github.com/Zennmn/AM-plus-plus/graphs/contributors)
- 上游提供的嵌入版：[npatch embedded edition](https://github.com/Zennmn/AM-plus-plus/releases/tag/embedded)（由上游维护，不能作为本分支安装包使用）

### 支持原作者

以下为上游原作者 **Zen** 的微信赞赏码，保留用于感谢和支持原作者，不代表本分支维护者的收款信息。赞赏完全自愿，不影响功能使用。

<p align="center">
  <a href="images/wechat-appreciation.png">
    <img src="images/wechat-appreciation.png" alt="原作者 Zen 的微信赞赏码" width="300">
  </a>
</p>

## 其他开源贡献

- [AMLyricBlur](https://github.com/a23bc/amlyricblur)：歌词模糊的参考与改编代码。
- [AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)：液态玻璃渲染、组件与交互。
- YouLyPlus / YouLy+：LLM 歌词翻译提示词与兼容接口处理的参考实现。
- [Tcrrry Lyrics Plugin](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin)：CJK 尾词渐变处理的参考实现。
- [Kifranei/AM-plus-plus-media-plugin](https://github.com/Kifranei/AM-plus-plus-media-plugin)：平板媒体控件、音频输出规则与图形资源。
- AMLL：TTML 歌词生态与平板播放器布局参考。
- [libxposed](https://github.com/libxposed)：模块运行所依赖的 Xposed API 与服务。

各项改编范围、版本、提交来源、版权和许可全文入口见 [第三方声明](../THIRD_PARTY_NOTICES.md)。本项目的许可证见 [LICENSE](../LICENSE)。原始版权声明、第三方许可与源码来源记录继续保留。

## Acknowledgments

LyricWave (澜音) is derived from [AM++ by Zennmn (Zen)](https://github.com/Zennmn/AM-plus-plus). Laoch832 maintains this development fork. We thank the original author, upstream contributors, and the open-source projects listed above. Existing upstream work is not claimed as original work of this fork.

The donation image above belongs to the original author, Zen. It is retained as an explicitly attributed way to support the upstream author. See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) for source provenance and licensing details.

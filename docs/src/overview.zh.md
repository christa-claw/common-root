# Common Root?

_研读塑造我们世界的文本。_

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.8.11 |
| Generated | 2026-09-22 |
<!-- /AUTOGEN:meta -->

## 这是什么

Common Root? 是一款免费、开放的阅读工具，用于并排探索亚伯拉罕诸教的经典——圣经各种译本、
《古兰经》、圣训以及注释——可使用任何语言，并深入到任何程度。

这个名字本身就是一份邀请。把这些文本并列阅读，亲自衡量它们有何共通之处，又在何处分歧。

本平台是独立的，不隶属于任何宗教组织或出版机构。阅读与研读始终免费；可选的免费账户还可让你
留下私人评论与笔记。

## 适合谁使用

学者、学生以及好奇的读者皆宜。无论你是在比较同一节经文在不同译本中的译法、在两约之间追踪一段
预言，还是像最早的受众那样把一卷书当作连续散文来读——本阅读工具的设计目标都是不妨碍你。

## 阅读如何运作

本阅读工具以**栏**为核心构建。每一栏显示一种文本——一种圣经译本、《古兰经》、一部注释，或你
自己的笔记。你可以打开任意数量的栏，并自由地混合不同传统。各栏默认一起滚动，使同一段落保持对齐，
任何一栏都可以解除链接以便独立浏览。

### 显示模式

本平台的一个核心理念是：我们习以为常的那些划分——章、节、段落标题——都是在文本写成很久之后才
添加的编辑性层次。每一栏都可以在显示或隐藏这些层次的模式之间切换：

- **Scriptio Continua（连续书写）** —— 仿照古代手稿风格的连续文本，没有章节划分。一卷书内的
  各章连贯排列；一条带标签的分隔线标示每卷书的边界。
- **章（1227）** —— 由斯蒂芬·朗顿（Stephen Langton）约于 1227 年添加的分章系统。
- **节（1551）** —— 由罗贝尔·埃斯蒂安（Robert Estienne）于 1551 年添加的节号。
- **标题** —— 现代编辑性段落标题，在译本提供时显示。

### 阅读顺序

每一栏还可以按不同顺序呈现其各卷书：传统的**正典**顺序，或学术性的**编年**顺序——后者将不同
书卷的段落交错排列，使先知书与诗篇能够置于其历史背景中阅读，而非孤立地阅读。

### 阅读《古兰经》

《古兰经》栏显示阿拉伯文文本，你可以选择一种译文显示在每节经文（aya）之下。阿拉伯文始终为主，
译文成对地配于其下、置于同一滚动栏中，使两者永不脱节。译文选择器仅提供该《古兰经》的译本。

### 可分享的链接

阅读工具的完整状态——每一栏的文本、所打开的段落、其显示模式与阅读顺序、任何译文叠加层，以及各栏
是否一起滚动——都会记录在页面 URL 中。复制链接即可日后回到完全相同的视图，或分享给他人。这些引用
与版本和语言无关，因此在一种译本中创建的链接，在另一种译本中也能正确打开。

## 当前可用的文本

<!-- AUTOGEN:translations -->
_BaseX query skipped (install `requests` to enable live ingestion stats)._
<!-- /AUTOGEN:translations -->

更多译本与传统——包括《古兰经》、各圣训集，以及多种语言的更多圣经版本——正在持续添加。

## 章节音频

公有领域的版本正在逐步获得朗读音频：每章一个 mp3，另有逐节的毫秒位置索引，使阅读行
能够跟随文本，单节经文也可以被链接。目前的覆盖范围：

<!-- AUTOGEN:audio -->
_No audio manifest at `audio/index.json`; coverage table not refreshed. Set `COMMONROOT_AUDIO_INDEX` to point at it._
<!-- /AUTOGEN:audio -->

这是尽力而为地让公有领域经文至少有音频形式，而不是出版级的有声圣经。朗读是合成的
——神经网络语音合成，每个版本一个声音，没有真人朗读者，也没有录音棚处理——并且在免费
字符额度内每晚只生成几章，因此一部圣经规模的版本需要数月才能补齐。只朗读公有领域文
本；许可证禁止演绎作品的版本被有意排除在外。

重点是覆盖那些一无所有的文本。1933 年芬兰语译本和德里慈希伯来文新约没有像样的音频
版本，而英语在别处已有充足供应——所以英语在队列中被刻意排在最后，而不是最前。

### 机器朗读会出的错

收听前值得知道，听到时也值得反馈：

- **专有名词。** 希伯来语和希腊语的人名常常读错。经文文本以纯连续文本送入引擎，没有
  发音词典，因此读错的名字不会被任何机制纠正。
- **古旧拼写。** 1776 年芬兰语早于现代正字法，引擎的文本规范化并非为它而建。数字、
  缩写和旧拼写只能按引擎的读法读出。
- **全程只有一个声音。** 叙述、对话和引语共用同一语调；音频从不标示说话者是谁。
- **节的时间点可能不准。** 章节是有意作为连贯散文合成的，让语句的停顿跨越节与节的
  分界，而不是在每一节都停下。时间点来自引擎经过每一节时报告的标记，通常精确——但
  如果它报告的标记少于该章的节数，该章仍会发布，缺口只记在日志里。因此少数章节的
  跟随可能不够精确。
- **长章节的接缝。** 超过约 7,500 个字符的内容会分段合成再拼接。接缝安排在节的边界，
  那里本就适合停顿，但偶尔仍能听出来。

已经生成的内容不会自动重新生成——磁盘上的一章就是已经付过费的一章——所以纠正一处糟糕
的朗读是一个刻意的动作：删除该章，让下一次夜间运行重新生成。

## 注释与论辩

除了第一手文本之外，本平台还收录并索引关于特定经文的论辩材料，取自亚伯拉罕诸传统内部的多种视角
以及批判性分析。这些材料与其所讨论的经文相互关联，使研读有争议段落的读者能够看到人们如何从不同
角度对其展开论辩。

来源按频道与视角加以组织：

<!-- AUTOGEN:channels -->
| Channel | Tradition | Content |
|---|---|---|
| Apologetics Roadshow | Christian | shorts,videos,streams |
| GodLogic Apologetics | Christian | shorts,videos,streams |
| Hatun Tash DCCI Ministries | Christian | shorts,videos,streams |
| Israel Advocacy | Christian | shorts,videos,streams |
| Shamounian Explains | Christian | shorts,videos,streams |
| The Crucible | Christian | shorts,videos,streams |
| JihadWatchVideo | Critical | shorts,videos,streams |
| Raymond Ibrahim | Critical | shorts,videos,streams |
| Ali Dawah | Islamic | shorts,videos,streams |
| DUS Dawah | Islamic | shorts,videos,streams |
| DawahWise | Islamic | shorts,videos,streams |
| Dr Zakir Naik | Islamic | shorts,videos,streams |
| Let the Quran Speak | Islamic | shorts,videos,streams |
| Mohammed Hijab | Islamic | shorts,videos,streams |
| Modern Day Debate | Neutral | shorts,videos,streams |
| Alpha & Omega Ministries | Unknown | shorts,videos,streams |
| Apologia Studios | Unknown | shorts,videos,streams |
| Bible Thinker | Unknown | shorts,videos,streams |
| Bob of Speaker's Corner | Unknown | shorts,videos,streams |
| Christ Over ALL | Unknown | shorts,videos,streams |
| Cross Examined | Unknown | shorts,videos,streams |
| DCCI Ministries | Unknown | shorts,videos,streams |
| Elijah Johnson Apologetics | Unknown | shorts,videos,streams |
| Jay Dyer | Unknown | shorts,videos,streams |
| Maybe God Podcast | Unknown | shorts,videos,streams |
| One God One Truth HQ | Unknown | shorts,videos,streams |
| SO BE IT | Unknown | shorts,videos,streams |
| Theological Apologia | Unknown | shorts,videos,streams |
| Towards Eternity | Unknown | shorts,videos,streams |
| Vlad Savchuk | Unknown | shorts,videos,streams |

_30 channels configured._
<!-- /AUTOGEN:channels -->

## 支持

Common Root? 是免费的，并将永远免费。如果它对你有用，你的支持有助于支付托管费用、获得授权译本的使用
权限，以及投入时间添加新文本与新功能。

Buy Me a Coffee: https://buymeacoffee.com/christaclaw

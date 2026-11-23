# 余烬工坊 MR

一款以《ForgeTax》的锻造、遗物构筑和巨龙贡契为基础设计的中文单人 MR 游戏。面向 PICO Space Pro 的 PICO OS 6 原生空间运行时，使用 PICO CLI 的 Stage 模板和 Spatial SDK 6.1.9。手机 Android 与 PICO OS 5 头显不在此包的运行范围内。[PICO 设备与系统说明](https://developer.picoxr.com/document/discover/faqs-for-project-swan-and-pico-os-6/)

## 安装与开局

将签名 APK 侧载到头显，打开应用库中的「余烬工坊 MR」。也可在开启开发者模式、允许 USB 调试后，在 APK 所在目录运行以下命令。

```powershell
pico-cli app install ".\EmberForgeMR-1.1.0-rc4-arm64.apk" --replace
```

RC4 沿用 1.0 至 RC3 的包名和签名。直接覆盖安装可以保留契约、房间锚点和输入设置。RC1 单独移过的工作台、料盒及读数板保留原来的自定义摆位，新增的贡台和遗物台从旧工作台向两侧展开。首次打开可先选「布置工坊」，使用初始摆法则可直接签「三日短约」。主菜单、暂停和选开炉赠礼的界面默认显示在面前 56 厘米。「设备与定位」中可以在 45、56 和 72 厘米间切换。点主页「启用眼动」，同意系统授权后，依次看设备页左、中、右三个点。白点随真实目光移动。设备页可区分未授权、未收到样本和头显当前不提供数据。手柄和裸手都可通过指向完成整局。

RC3 把游戏中的注视判定半径扩大一倍。看矿料时，视线距矿心 17.2 厘米以内即可进入候选，相邻矿料的范围重合时优先选择中心更接近视线方向的可见矿料。看剑上判定点时，视线在剑面上的交点距靶心 4.8 厘米以内，青色锁定环就开始合拢。RC4 将锁定从 175 毫秒缩短至约 88 毫秒，选料仍为 220 毫秒。随后仍需用锤面敲中剑身，才能吃到这次注视的精准加成。

## 把工坊摆进房间

- **抓把手。** 锻造台、矿料架、贡台、遗物台和读数板各有自己的把手。主锻造台面为 98 × 56 厘米，三格料架为 64 × 34 厘米，贡台为 48 × 38 厘米，遗物台为 54 × 46 厘米。用手柄握键或裸手捏住，移动、转腕、松开。远处可对准把手抓取。改变料盒位置时，矿石和它们的选中框一起移动。
- **贴实桌。** 四个实体工位都有各自的落地支架。将其中一个移到真实桌面或柜面上方，再点「贴到实桌」，贴合成功后会收起该工位支架。也可在布置面板手动切换支架和桌放。头显须已识别出能承托家具的桌面或柜面。扫描暂不可用时，可用「微调」设定高度、前后、左右和朝向。
- **保存。** 「保存」记住家具之间的摆法。「保存并固定到房间」另外请求房间锚点，下次启动尝试找回真实位置。当前房间未识别时仍可玩，回到原处可在设备页点「找回房间」。在一次布置中可撤销，点「取消」会回到上次保存的布局。
- **找回面板。** 同时握紧两个手柄的握键，或双手同时捏合，保持 1.5 秒，可在面前呼出布置面板。在布局页可把单件家具带到面前，设备页也可把整间工坊移到面前。

## 一把剑怎么玩

1. **取料。** 指向矿石，或启用眼动后直接看向矿石。专用青色分段环从亮起到合拢表示目光已锁定。握住手柄握键或裸手捏合，移到锻造台的铁砧上方松开。坐得较远时，也可以在板上选择「取料」「放上铁砧」。
2. **落锤。** 剑胚上砧后，握柄随手柄或手掌移动，转腕会带动锤头。抬起一小段再落锤，让正面的银色锤面敲到剑身。锤面敲中光环，或用眼动看稳光环后敲中剑身，都能得到精准加成。精准锤将损毁签的相对权重减半，成功或大成功时结算剑值再乘 1.25，入神前另加一格蓄能。每次有效落锤会更新光环位置。需要坐在较远处时，可在设备页主动开启「辅助落锤」，再用扳机、捏合或板上按钮让辅助锤挥下。
3. **收手。** 到有铁栏龙眼窗的贡台可以把剑值存入当日贡盘。三格陈列柜前的遗物炉收下一把剑，换取贯穿本次契约的遗物。每件遗物都写明效果。幽商到访时，可用 +3 或以上的剑换取永久影响本局概率的签券。
4. **敲钟。** 每天有三枚剑胚。日末核算当日贡值，三次欠贡会失去契约。长约持续二十四日，完成后可续签无尽，或用自己锻出的最高价值挑战守印巨龙。

三种矿会改变同一套锻造规则。磁铁矿更稳，星银矿提高起价，龙晶矿同时增加大成功和碎裂的机会。同一把剑蓄满十格后，下一锤进入狂热。基础持续 4 秒，有对应遗物可延长。点燃狂热的那一锤会在砧脚激起一道向外扩开的火弧和短火星，伴随金属起音。使用手柄时，持锤一侧还有一次更有分量的振动。砧边和账板的深色字牌上出现鲜艳的七色「狂热入神」和秒数，剑身也会变色。最后两秒能听到六声逐渐加紧的短音，字牌同步跳动，砧边火带在末段向内收束。在倒计时内，损伤和损毁概率都为零，仍可能抽到维持、成功或大成功。倒计时结束后恢复普通赔率。

持锤后，把锤头抬起约十厘米再下落。另一侧手柄握住握键，或另一只裸手持续捏合约半秒，即可换手。普通锤与精准锤的火花有不同颜色，火花从实际敲中的位置迸出。

每天有一份可选委托。看读数板上的指定矿种、品级或单剑精准锤次数，把符合条件的剑放进贡盘，当日就能额外得一次贡值。转动头部或改变料盒位置时，耳机里锤声、取料声与敲钟的位置感也会随之变化。

## 改编依据

五类结果、单剑入神、以剑换遗物、贡钟、二十四日契约和幽商签券均参考原作资料。早期实玩资料描述必须预热。开发者十一月更新取消了这一步，本版也允许剑坯上砧后直接锻造。三日短约、矿石差异、锤面精准判定、八件遗物的数值与组合、终章破印玩法均为本作的房间尺度 MR 改编。场景模型、矿石纹理、界面、封面和音效为本项目制作的资源。[Steam 商店](https://store.steampowered.com/app/5245810/ForgeTax/) · [开发者更新与平衡说明](https://steamcommunity.com/app/5245810/allnews/) · [玩家实玩记录](https://vgtip.3loumao.org/article/forgetax-kuai-su-shang-shou-zhi-nan.html)

RC2 的工位地标、锤击反馈与蓄能读数参考了官方逐帧实录。原作的基础狂热防碎，仍可损伤。本版按玩家反馈，在整段狂热中也免除损伤。[官方锻打实录](https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/5245810/extras/2b4d792f6f7ecc29df69ab0634fc4bb8.mp4) · [遗物柜实录](https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/5245810/extras/57f26ebd25e178d3e2e93824d4821d18.mp4) · [11 月 12 日官方更新](https://store.steampowered.com/news/app/5245810/view/670633736808694118) · [基础狂热机制](https://www.forgetax.tools/guides/frenzy)

## 工程

`app/src/main/java/com/pondledger/mr` 包含可重放的游戏规则、保存逻辑、SpatialUI 读数板、场景与追踪输入。纹理在 `app/src/main/assets/forge`，本地音效在 `app/src/main/res/raw`。已配置 Android SDK 35 和 PICO Spatial 工具链的本机可在工程根目录运行 `.\gradlew.bat assembleDebug` 生成开发安装包。

<details>
<summary>游戏材质与生成提示</summary>

工坊使用十四张 GPT Image 生成的基础色纹理。RC3 新增橡木、山核桃木、黄铜、铜、打磨钢、发黑铁、皮革、酒红色毛毡、耐火砖和青绿色搪瓷，搭配原有的锤纹铁与三种矿石。金属度和粗糙度按表面单独设置。沿板长的木纹、顺锤柄展开的纹理和顺剑身延展的磨纹使用各自的 UV。锤面、剑身和黄铜镶边各有不同的反光，炉内耐火砖与贡盘毡衬呈哑光。

**锻铁面**

> Generate one square, edge-to-edge, seamless tileable BASE COLOR / ALBEDO texture for a tabletop video game asset. A close orthographic scan of a single continuous sheet of aged blue-grey hand-forged iron with subtle variations of gunmetal charcoal, scattered tiny silvery hammer dimples, restrained local copper-brown oxidation and polished rubbed areas. Scale feels like 20 cm of work surface. Medium grey overall (clearly visible), low-frequency variation and tactile detail, no busy high contrast. Even neutral diffuse scanner illumination, no directional lighting, no shiny hotspots, no cast shadow. Absolutely no objects, borders, ornament, holes, lettering, text, grid or atlas divisions. The entire frame is the raw flat material, all edges continue naturally. Physically plausible, artisanal dark-fantasy foundry.

**矿石纹理图集**

> Create a game production material texture sheet, a wide 3:1 landscape image split at exactly one-third and two-thirds into THREE equal square edge-to-edge mineral albedo tiles (triptych, perfectly straight abutting vertical divisions). No outlines or gaps. Camera top-down flat macro material scan, even diffuse light. Left third: raw magnetite, charcoal charcoal-blue crystalline ironstone, silver metallic flecks, granular magnetite crystals and granular grey mineral veins. Center third: a luminous grey silver-bearing rock, pale silver-white native metal veins in cool teal schist, large diagonal wandering vein, clear value contrast. Right third: dragon crystal, deep wine red volcanic matrix with vivid vermilion to honey amber angular quartz veins and red crystal facets, visibly different from silver. Each is a continuous natural surface across its entire tile, not an isolated object. Realistic, crafted, tactile, suitable for wrapping on low-poly mineral pickup items in an MR tabletop game. No text, objects, background, ambient light gradient, bloom, reflections, frames, engraving, film effects, gaps.

</details>

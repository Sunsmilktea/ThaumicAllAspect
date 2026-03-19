# Thaumic All Aspect

面向 **Minecraft 1.7.10 / Thaumcraft 4** 的附属模组，用于**自动为缺少研究要素的内容补全要素**，尽量保持平衡与原版风格。

---

## 功能简介

- **物品 / 方块 / 流体自动补全要素**
  - 优先从 **NEI 中可见的合成 / 熔炼配方** 汇总原料要素（本模组作为 NEI 附属使用，前提是装有 NEI+TC4+Baubles）；
  - 在无 NEI 或 NEI 适配失败时，回退到原版 `CraftingManager` / `FurnaceRecipes` / Thaumcraft 配方索引；
  - 若无合成表，则按**方块 / 物品类型（继承关系）**分配要素；
  - 要素数量默认**衰减 90%**（至少保留 1 点），避免过于泛滥。

- **生物要素自动补全**
  - 依据**实体继承关系、种类、掉落物**等信息分配；
  - **怪物**不额外添加野兽（`BEASTIA`）要素；
  - **普通生物**（非怪物）固定额外获得 **4 点野兽**要素；
  - 整体同样遵循**衰减 90%**的规则。

- **非侵入式设计**
  - **只处理“当前没有任何要素”的目标**；
  - 不会覆盖其它模组 / 脚本已经注册过的要素；
  - 适合作为整合包里的“兜底补全”方案。

---

## 环境与依赖

- **Minecraft**：1.7.10  
- **Forge**：推荐 10.13.4.1614+  
- **Thaumcraft**：4.2.3.5+

如用于整合包，请确保上述依赖版本满足或高于推荐版本。

---

## 安装方式

1. 从 Release / 构建产物中获取 `ThaumicAllAspect-xxx.jar`；
2. 将 Jar 文件放入整合包 / 客户端 / 服务器的 `mods/` 目录；
3. 确保已安装对应版本的 Forge 与 Thaumcraft 4；
4. 启动游戏后，在 `Mods` 列表中确认本模组已加载。

---

## 开发与构建

本项目使用 **Gradle** 构建，仓库自带 `gradlew` 脚本。

### 本地构建

在项目根目录执行：

```bash
./gradlew build
```

构建完成后，产物位于：

- `build/libs/` 目录下的 Jar 文件。

### Windows 用户

```bash
gradlew.bat build
```

如遇到 Gradle 下载缓慢，可自行配置国内镜像源。

---

## 运行时行为与配置

### 配方优先多轮推导（Recipe-first pipeline）

- 启动时会先构建配方索引（原版 / 熔炉 / TC 索引先建，NEI 再**合并追加**），然后运行若干轮“配方优先”推导管线（默认最多 6 轮，可在配置中调整）：
  - 第 1 轮：对所有配方，当 **所有输入已有要素** 时，为输出赋值（输入之和 × `recipeDecay`）；
  - 若某配方有任一输入无要素，则将该输出记入“待重算集合”，留给下一轮；
  - 后续轮次仅对待重算集合对应的配方重试，直到没有新产物或达到配置中的最大轮数。
- 这样能正确处理诸如 `A+B=C`、`A=B`（仅 A 有 3 点要素）的情况，最终得到：`B → 3`、`C → 6`。对于一些链条特别长的大包，可以通过配置放宽最大轮数。

### 关键修复：避免“0 要素/假要素”
- 修复了从 TC 或缓存读取时出现的异常：当 `AspectList` 的 `size > 0` 但所有要素总量 `total == 0` 时，之前可能会被误判为“已有要素”，从而跳过重推导，导致 HUD/机器读数出现 `=0`（甚至引发炉子/机器崩溃）。
- 现在统一把“总量为 0 的 AspectList”视为“无要素”，强制走推导/兜底；并在写入 TC、缓存文件以及日志前，保证每个要素的数量至少为 1。
- 针对带“粒/小件”类命中词（如 `nugget/粒/dust/shard`）且所有要素恰好为 1 的物品，增加 UI 可见性增强：自动把要素数量整体从 1 扩大到 2（不改变要素类型集合）。

### 配置缓存与增量扫描

- 扫描结果会写入 `config/ThaumicAllAspect/aspect-cache.cfg`：
  - **存在缓存文件时**：先加载缓存作为**初始要素种子**，随后仍会执行完整扫描（含 6 轮配方推导与兜底），只为**尚无要素**的物品补全，**不会覆盖**已有要素；结束后写回更新后的缓存。
  - **删除缓存文件时**：下次启动从零执行完整扫描（含 6 轮配方推导与兜底），并生成新缓存。
- 整合包作者推荐做法：
  - 在开发环境中跑一遍完整扫描，生成 `aspect-cache.cfg`；
  - 可将此文件随整合包分发；玩家每次启动仍会基于缓存做一轮增量补全，适配新增模组或配方。

### 可调参数与高级选项

- `config/ThaumicAllAspect/thaumicallaspect.cfg` 中提供了一些可调节的性能 / 精度开关：
  - `recipeDecay`：全局推导衰减系数（默认 0.1），越低代表每层配方继承的要素越少；  
  - `maxRecipeDepth`：配方推导时允许的最大递归深度（默认 10），用于防止配方相互引用导致死循环；
  - `recipeFirstMaxRounds`：配方优先管线的最大轮数（默认 6），可以根据整合包规模调高或调低；
  - 其余如日志详细程度、是否跳过部分诊断输出等，也都可以在该 cfg 中调整。

### 兜底与可配置行为

- 关键词兜底（按语言分组：中 / 英 / 俄 / 日 / 韩 / 德 / 法 / 西）；
- 关键字兜底配置：`config/ThaumicAllAspect/keyword-fallback.cfg`；
- 单物品 / 方块 / 流体兜底配置：`config/ThaumicAllAspect/item-fallback.cfg`；
- 所有配置文件统一放在 `config/ThaumicAllAspect/` 目录下，避免散落。

---

## 计划与状态

当前为 **早期版本 / 移植整理中**，后续可能的方向包括（但不限于）：

- 增加更多可配置项（例如衰减倍率、黑名单 / 白名单等）；  
- 与脚本系统（如 Minetweaker/Modtweaker）更好协作；  
- 提供更详细的调试 / 日志输出，便于整合包作者微调平衡。

---

## 反馈与贡献

- 如果在使用过程中遇到崩溃或要素分配异常，欢迎通过 GitHub Issues 反馈：  
  请附上 **日志、崩溃报告、模组列表** 等信息；  
- 欢迎提交 PR 修复 Bug 或改进代码 / 文档。

---

## 许可 License

本模组以 **GNU LGPL v3** 协议发布：

- 你可以在整合包中自由使用和分发本模组；  
- 你可以修改并发布修改版，但对本模组源码的修改部分需要在 LGPL v3 兼容的条件下开放源代码；  
- 在此基础上，你的整合包或依赖本模组的其他模组可以采用任意协议（包括闭源 / 商业），只要不把本模组的 LGPL 代码直接并入闭源部分。

The mod is licensed under **GNU LGPL v3**:

- You are free to use and distribute it in modpacks.  
- You may modify and redistribute it, as long as your modifications to this library remain under LGPL‑compatible terms and the corresponding source is available.  
- Your own modpack or other mods can use any license (including proprietary), as long as they do not directly absorb LGPL‑licensed code into closed‑source components.

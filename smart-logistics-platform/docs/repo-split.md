# P2-9：拆出独立仓库操作指南

> 目的：把 `smart-logistics-platform` 从多项目一仓（CJJ_JAVA_WORKSPACE）拆成独立仓库。
> CI / 简历展示 / 授权边界随之全部理顺——workflow 不再需要 paths 过滤与 working-directory 补丁。

## 前置（一次性）

```bash
brew install git-filter-repo
```

## 步骤

### 1. 在 GitHub 建一个空仓库（不要初始化 README/.gitignore）

仓库名建议 `smart-logistics-platform`。

### 2. 裁剪历史并推送（保留只涉及本项目的提交）

```bash
cd /tmp && git clone --no-local ~/freecodecamp/CJJ_JAVA_WORKSPACE aslp-split
cd aslp-split
git filter-repo --subdirectory-filter smart-logistics-platform --force
git remote add origin https://github.com/<你的用户名>/smart-logistics-platform.git
git push -u origin main
```

### 3. 简化 workflow（拆完后 `.github/workflows/aslp-ci.yml` 改为）

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: mvn -B verify      # 含 JaCoCo 覆盖率门禁（P0-3）
      - uses: actions/upload-artifact@v4
        if: failure()
        with: { name: surefire-reports, path: '**/target/surefire-reports/**' }
```

变化：删 `paths` 过滤、删 `working-directory`、删 `cache-dependency-path` 前缀——
独立仓库里 workflow 在自己根下，GitHub 直接识别。

### 4. 收尾

- 原 CJJ_JAVA_WORKSPACE 里删除 `smart-logistics-platform/` 子目录（避免双权威源）；
- `todo.md` §13.1「多项目工作区」说明已过时，改为"独立仓库"描述；
- 新仓库 Settings → About 填描述 + topics（`spring-boot` `microservices` `testcontainers`）。

## 不用 filter-repo 的替代（历史归零，5 分钟版）

直接把项目文件夹移出 → `git init` → 建远程 → push。
代价：两年提交历史不随行（todo.md 文字台账仍在）。

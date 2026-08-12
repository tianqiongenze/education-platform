# 前端 UI 测试用例集

> **版本**: 1.0 | **日期**: 2026-08-09 | **适用**: QA 人工 UI 测试
>
> 覆盖 3 个前端 app: portal-web (门户) / admin-web (管理后台) / student-h5 (学生端)。
> 测试浏览器建议: Chrome 最新版 + Safari (H5 兼容)。
> 截图工具: macOS `Cmd+Shift+4` 或浏览器 DevTools 截屏, 文件名约定 `TC-UI-XXX-<日期>.png`。

通用前置:
- bff-java (8090) 与相关后端已启动且 `/actuator/health` 返回 UP
- 测试账号: 学生 `student9000/Pass123!`; 教师 `teacher9500/Pass123!`; 管理员 `admin8000/Pass123!`

---

## portal-web (www.edu.com / localhost:3004)

门户站点, 公开访问 + 统一登录入口。路由来源 `frontend/apps/portal-web/src/pages`。

### TC-UI-001: 首页加载

**前置**: portal-web 已启动 (`pnpm dev --port 3004`)
**步骤**:
1. 打开浏览器访问 `http://localhost:3004`
2. 等待页面完全加载 (网络面板无 pending 请求)
**预期**:
- [ ] Hero 区域显示平台名称 "智教云 — 全场景在线教育平台" 与标语
- [ ] "浏览课程" / "立即登录" 两个 CTA 按钮可见且可点击
- [ ] 统计 KPI 4 张卡片显示 (优质课程 / 注册学员 / 资深讲师 / 企业客户)
- [ ] 推荐课程卡片至少 4 张 (走真实 API, 失败时回退 mock 数据)
- [ ] 事业群入口 3 张 (业务中台 / IT 培训 / 知识中台)
- [ ] 页脚链接可点击
- [ ] 浏览器控制台无 error 级日志
📸 截图保存

### TC-UI-002: 课程目录页 (/courses)

**前置**: TC-UI-001 通过
**步骤**:
1. 在首页点击 "浏览课程" 按钮, 或直接访问 `http://localhost:3004/courses`
2. 在搜索框输入 "CAP" 并回车
3. 点击任一分类筛选标签
**预期**:
- [ ] 课程列表分页展示, 每页默认 10 条
- [ ] 搜索结果按关键字过滤
- [ ] 每张课程卡片显示: 封面 / 标题 / 讲师 / 价格
- [ ] 点击卡片跳转到课程详情页
- [ ] 分页器可正常翻页
📸 截图保存

### TC-UI-003: 课程详情页 (/courses/:id)

**前置**: 至少存在 1 个已发布课程
**步骤**:
1. 访问 `http://localhost:3004/courses/1`
2. 未登录状态下查看页面
3. 点击 "立即报名" 按钮
**预期**:
- [ ] 显示课程标题 / 描述 / 讲师信息 / 价格 / 大纲
- [ ] 未登录时点击 "立即报名" 跳转到 `/login`
- [ ] 登录后报名按钮变为可点击, 跳转下单流程
📸 截图保存

### TC-UI-004: 统一登录页 (/login)

**前置**: portal-web 已启动
**步骤**:
1. 访问 `http://localhost:3004/login`
2. 留空用户名/密码, 点击 "登录" 按钮
3. 输入错误密码 `wrong`, 提交
4. 输入正确凭据 `student9000 / Pass123!`, 提交
**预期**:
- [ ] 表单显示 "智教云统一登录" 标题
- [ ] 空表单提交时显示必填校验提示
- [ ] 错误密码时弹出 `message.error('登录失败...')` 红色提示
- [ ] 正确凭据登录后 `message.success('登录成功')` 并跳转首页
- [ ] localStorage 中写入 token
📸 截图保存 (3 张: 校验 / 错误 / 成功)

### TC-UI-005: 事业群导航页 (/bu) 与 关于页 (/about)

**前置**: portal-web 已启动
**步骤**:
1. 访问 `/bu`, 点击 3 张事业群卡片
2. 访问 `/about`
**预期**:
- [ ] `/bu` 显示 3 大事业群卡片式入口, 点击跳转到对应子平台
- [ ] `/about` 显示平台特性与技术亮点
📸 截图保存

### TC-UI-006: 404 页面

**步骤**: 访问 `http://localhost:3004/not-exist-route`
**预期**:
- [ ] 显示友好的 404 提示页
- [ ] 提供 "返回首页" 链接
📸 截图保存

---

## admin-web (localhost:5173)

管理后台, 路由前缀 `/dashboard`、`/courses`、`/business/users` 等 (来自 `frontend/apps/admin-web/src/routes/index.tsx`)。
所有受保护路由经过 `AuthGuard`, 未登录跳转 `/login`。

### TC-UI-101: 管理员登录

**前置**: admin-web 已启动 (`pnpm dev --port 5173`)
**步骤**:
1. 访问 `http://localhost:5173/login`
2. 输入 `admin8000 / Pass123!`, 点击登录
**预期**:
- [ ] 登录成功后 `localStorage` 写入 `ACCESS_TOKEN` 与 `REFRESH_TOKEN`
- [ ] 调用 `/api/user/profile` 拉取用户信息
- [ ] 自动跳转到 `/dashboard`
- [ ] 顶部导航栏显示用户头像与角色
📸 截图保存

### TC-UI-102: 未登录访问受保护路由

**步骤**: 清空 localStorage, 直接访问 `http://localhost:5173/dashboard`
**预期**:
- [ ] `AuthGuard` 重定向到 `/login`
- [ ] URL 上携带 `?from=/dashboard` (用于登录后回跳)
📸 截图保存

### TC-UI-103: 仪表盘 (/dashboard)

**前置**: TC-UI-101 通过
**步骤**:
1. 进入 `/dashboard`
2. 观察 KPI 卡片与图表
3. 切换时间范围筛选 (今日 / 本周 / 本月)
**预期**:
- [ ] KPI 卡片: 用户总数 / 订单总数 / 课程总数 / 今日营收
- [ ] 图表正确加载 (折线 / 柱状)
- [ ] 时间范围切换后数据刷新
📸 截图保存

### TC-UI-104: 用户管理 (/business/users)

**前置**: 已登录管理员
**步骤**:
1. 进入 `/business/users`
2. 点击 "新建用户" 按钮 (`/business/users/create`)
3. 填写用户名 / 手机 / 邮箱 / 角色 / 密码, 提交
4. 在列表中搜索刚创建的用户
5. 点击 "冻结" 操作
**预期**:
- [ ] 列表分页展示, 支持按用户名 / 手机号搜索
- [ ] 新建表单字段与后端 `CreateUserCmd` 一致 (username/phone/email/password/nickname)
- [ ] 创建成功后列表刷新, 出现新用户
- [ ] 冻结操作弹出确认框, 确认后状态变为 FROZEN
- [ ] 表单校验: 手机号 11 位, 邮箱格式, 密码强度
📸 截图保存 (列表 / 表单 / 校验)

### TC-UI-105: 课程管理 (/business/courses)

**前置**: 已登录管理员
**步骤**:
1. 进入 `/business/courses`
2. 点击 "新建课程" (`/business/courses/create`)
3. 填写 title / description / teacherId / category / difficulty / price, 提交
4. 进入新课程详情页 `/business/courses/:id`
5. 点击 "发布" 按钮
**预期**:
- [ ] 表单字段对齐 `CreateCourseCmd`
- [ ] `price` 字段支持小数 2 位
- [ ] 发布成功后状态徽标变为 "已发布"
- [ ] 课程详情页显示大纲 / 章节 / 报名学员列表
📸 截图保存

### TC-UI-106: 订单管理 (/business/orders)

**前置**: 已存在至少 1 笔订单
**步骤**:
1. 进入 `/business/orders`
2. 按状态 (CREATED/PAID/REFUNDED) 筛选
3. 点击订单号进入详情 `/business/orders/:id`
4. 点击 "退款" 按钮
**预期**:
- [ ] 列表显示 orderId / 用户 / 课程 / 金额 / 状态 / 创建时间
- [ ] 状态筛选生效
- [ ] 详情页显示订单完整生命周期 (含支付/退款记录)
- [ ] 退款操作弹出确认框, 确认后触发 `POST /api/payments/{orderId}/refund`
📸 截图保存

### TC-UI-107: 作业管理 (/it-backend/assignments)

**步骤**:
1. 进入 `/it-backend/assignments`
2. 点击 "创建作业", 填写 title/description/courseId/teacherId/maxScore/deadline, 提交
3. 点击作业进入详情 `/it-backend/assignments/:id`
4. 点击 "发布"
**预期**:
- [ ] 创建表单字段对齐 `AssignmentCommand`
- [ ] `deadline` 字段为日期时间选择器
- [ ] 发布后状态变更为 PUBLISHED
📸 截图保存

### TC-UI-108: 提交列表与评分 (/it-backend/submissions, /it-backend/grading/:id/grade)

**步骤**:
1. 进入 `/it-backend/submissions`
2. 选择一个未评分的提交, 点击 "评分"
3. 在 `/it-backend/grading/:submissionId/grade` 页填入 score + feedback
4. 查看评分流水线页 `/it-backend/grading/:submissionId`
**预期**:
- [ ] 提交列表显示学生 / 作业 / 提交时间 / 状态
- [ ] 评分页支持 0-100 数值输入 + 文本反馈
- [ ] 提交评分后调用 `POST /api/v1/submissions/{id}/grade`
- [ ] 流水线页展示 AI 评分 / 教师复核 / 查重 各阶段时间线
📸 截图保存

### TC-UI-109: 教师管理 / 学生管理 / 直播管理 / 编程训练

**步骤**: 分别访问 `/teachers`、`/students`、`/live`、`/coding`
**预期**:
- [ ] 各页面均能正常加载, 列表分页工作
- [ ] 无控制台 error
📸 截图保存 (每页 1 张)

### TC-UI-110: 系统设置 (/settings)

**步骤**: 进入 `/settings`, 修改 1-2 项配置 (如缓存策略/限流阈值), 保存
**预期**:
- [ ] 配置项正确读取
- [ ] 保存成功后弹出 `message.success`
📸 截图保存

### TC-UI-111: 403 与 404 页面

**步骤**:
1. 用学生 token 访问 `/business/users` (应越权)
2. 访问 `/not-exist`
**预期**:
- [ ] 越权时跳转 `/403` 页
- [ ] 未知路由跳转 `*` NotFound 页
📸 截图保存

---

## student-h5 (localhost:3001)

学生端 H5 应用, 移动端优先布局 (来自 `frontend/apps/student-h5/src/pages`)。

### TC-UI-201: 学生登录

**前置**: student-h5 已启动 (`pnpm dev --port 3001`)
**步骤**:
1. 访问 `http://localhost:3001/login`
2. 输入 `student9000 / Pass123!`, 登录
**预期**:
- [ ] 移动端表单布局 (单列, 大输入框)
- [ ] 登录成功后跳转 `/index`
- [ ] 底部 Tab 导航显示: 首页 / 课程 / 学习 / 我的
📸 截图保存 (DevTools 切到 iPhone 14 Pro 视图)

### TC-UI-202: 课程列表与详情

**步骤**:
1. 点击底部 Tab "课程"
2. 进入课程详情 `/course-detail/:id`
3. 点击 "立即学习" 或 "报名"
**预期**:
- [ ] 课程列表支持垂直滚动加载更多
- [ ] 详情页显示课程封面 / 简介 / 章节列表
- [ ] 点击章节进入 `/learning/:courseId/:sectionId`
📸 截图保存

### TC-UI-203: 学习页 (播放/阅读)

**步骤**:
1. 进入 `/learning/:courseId/:sectionId`
2. 播放视频 (或阅读图文)
3. 进度达 80% 后触发 "完成"
**预期**:
- [ ] 视频播放器加载正常, 支持暂停/拖动
- [ ] 学习进度持久化 (刷新页面后恢复)
- [ ] 完成章节后状态变更为 "已完成"
📸 截图保存

### TC-UI-204: 作业提交

**前置**: 存在已发布作业
**步骤**:
1. 在课程详情或学习页点击 "去作业"
2. 在富文本编辑器中作答
3. 点击 "提交"
**预期**:
- [ ] 富文本编辑器加载 (支持基础格式)
- [ ] 提交调用 `POST /api/v1/submissions`
- [ ] 提交成功弹出提示, 列表状态变为 "已提交"
- [ ] 同一作业重复提交时使用相同 idempotencyKey 防重复
📸 截图保存

### TC-UI-205: 我的订单与退款

**步骤**:
1. 进入 "我的" → "我的订单"
2. 查看订单状态
3. 对已支付订单点击 "申请退款"
**预期**:
- [ ] 订单列表按时间倒序展示
- [ ] 状态徽标颜色区分 (待支付/已支付/已退款)
- [ ] 退款流程调用退款 API, 提交后状态变为 "退款中"
📸 截图保存

### TC-UI-206: 个人中心 (/profile)

**步骤**:
1. 进入 "我的" → 个人资料
2. 修改头像 / 昵称
3. 退出登录
**预期**:
- [ ] 头像支持上传 (走文件上传 API)
- [ ] 昵称修改后立即生效 (`PUT /api/v1/users/{id}`)
- [ ] 退出登录清空 token, 跳转 `/login`
📸 截图保存

---

## 跨端通用 UI 验收

### TC-UI-301: 响应式布局

**步骤**: 用 DevTools 设备模拟器分别测试 portal-web (桌面 1440 / 平板 768 / 手机 375)
**预期**:
- [ ] 桌面: 多列网格布局
- [ ] 平板: 双列布局
- [ ] 手机: 单列堆叠, 汉堡菜单
📸 截图保存 (3 个尺寸)

### TC-UI-302: 浏览器兼容性

**步骤**: 在 Chrome / Safari / Edge 最新版各运行一次核心流程 (登录 → 课程 → 下单)
**预期**:
- [ ] 3 个浏览器均无 layout 错乱
- [ ] 视频播放与文件上传功能一致
- [ ] Safari 上无 WebKit 私有 API 报错
📸 截图保存

### TC-UI-303: 弱网与离线

**步骤**: DevTools Network 限速到 3G, 重载首页; 再切换到 Offline
**预期**:
- [ ] 3G 下首屏 < 5s 加载, 显示骨架屏
- [ ] 离线时已有缓存数据可读, 写操作提示 "网络不可用"
📸 截图保存

### TC-UI-304: 国际化与可访问性

**步骤**:
1. 切换浏览器语言为 en-US, 查看是否有英文文案缺失
2. 用键盘 Tab 遍历首页焦点元素
3. 用 axe DevTools 扫描可访问性
**预期**:
- [ ] 缺失翻译时回退到中文, 无 raw key 显示
- [ ] 所有交互元素可达键盘焦点, 顺序合理
- [ ] axe 扫描 0 个 critical 问题
📸 截图保存

---

## 附录: 截图命名与归档

- 命名: `TC-UI-<编号>-<步骤>-<yyyymmdd>.png` (例: `TC-UI-004-3-login-error-20260809.png`)
- 归档: `docs/testing/screenshots/<release-version>/`
- 每轮回归后归档目录打包上传到测试管理平台, 报告中附链接

## 附录: 缺陷优先级

| 级别 | 定义 | 示例 |
|------|------|------|
| P0 | 阻断核心流程, 无法绕过 | 登录失败, 列表 404, 下单报错 |
| P1 | 影响主要功能, 有绕过方法 | 校验不生效, 按钮无反馈 |
| P2 | 体验问题 | 文案错别字, 间距不对, 弱网无骨架屏 |
| P3 | 优化建议 | 动画卡顿, 颜色不一致 |

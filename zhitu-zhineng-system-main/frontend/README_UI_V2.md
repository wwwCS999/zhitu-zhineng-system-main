# 职途智配前端 UI V2

本版本只重构前端，不修改任何 Spring Boot 接口、数据库结构或业务逻辑。

## 视觉方向

- 深墨绿色顶部导航，替代固定左侧栏
- 暖白画布、米白卡片、薄荷绿与芥末金语义色
- 8–10px 克制圆角、1px 结构边框、极轻阴影
- 统计卡、进度条、时间线和图谱信息采用清晰的编辑式排版
- 页面进入、列表级联、进度条、消息思考状态和背景氛围均包含轻量动效
- 支持桌面、平板和手机响应式布局
- 支持 `prefers-reduced-motion`，用户关闭动效时自动降级

## Windows 替换步骤

1. 停止当前前端终端中的 `npm run dev`。
2. 备份原来的 `frontend` 文件夹。
3. 将本压缩包中的 `frontend` 文件夹覆盖到项目根目录。
4. 在 PowerShell 中进入前端目录：

```powershell
cd frontend
```

5. 重新安装依赖：

```powershell
npm install
```

6. 启动：

```powershell
npm run dev
```

7. 浏览器访问：

```text
http://localhost:5173
```

后端仍需同时运行在 `http://localhost:8080`。

## esbuild 脚本提示

若 npm 显示 esbuild 安装脚本尚未批准，执行：

```powershell
npm approve-scripts esbuild
npm rebuild esbuild
npm run dev
```

## 主要页面

- 系统总览
- 数据治理
- JD 解析
- 新岗位发现
- 岗位能力演化
- 岗位能力图谱
- 人岗匹配诊断
- 学习路径规划
- 可信审核
- 多智能体问答

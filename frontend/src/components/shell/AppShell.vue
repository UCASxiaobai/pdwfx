<!-- vue2-done -->
<template>
  <div class="app-shell">
    <header class="top-bar">
      <h1 class="sys-title">通信侦获信号分析系统</h1>
      <nav class="nav-links">
        <a href="#/" :class="{ active: mode === 'main' }">主流程</a>
        <span class="sep">·</span>
        <a href="#/stream" :class="{ active: mode === 'stream' }">流式态势</a>
        <span class="sep">·</span>
        <a href="#/ff-scatter" :class="{ active: mode === 'ff-scatter' }">PrcFf 标绘</a>
      </nav>
    </header>
    <main class="main-area">
      <slot />
    </main>
  </div>
</template>

<script>
export default {
  name: "AppShell",
  props: {
    mode: {
      type: String,
      default: "main"
    }
  }
};
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.top-bar {
  min-width: 800px;
  height: 65px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background-color: var(--theme-header-bg);
  background-image: var(--theme-header-bg-image);
  background-size: 100% 100%;
  border-bottom: 1px solid var(--theme-border);
}
.sys-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--theme-header-text);
  background: -webkit-linear-gradient(
    bottom,
    var(--theme-header-gradient-start) 40%,
    var(--theme-header-gradient-end) 60%
  );
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.nav-links {
  font-size: 13px;
}
.nav-links a {
  color: var(--theme-text-secondary);
  text-decoration: none;
}
.nav-links a:hover,
.nav-links a.active {
  color: var(--theme-accent-bright);
}
.sep {
  margin: 0 8px;
  color: var(--theme-border);
}
.main-area {
  flex: 1;
  min-width: 1000px;
  background-color: var(--theme-bg-primary);
}
</style>

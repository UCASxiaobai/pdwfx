import Vue from "vue";
import ElementUI from "element-ui";
import "element-ui/lib/theme-chalk/index.css";
import "@/styles/theme.scss";
import "@/styles/global.scss";
import "@/styles/cet36-panel.scss";
import "@/styles/element-ui.scss";
import App from "./App.vue";

Vue.use(ElementUI);
Vue.config.productionTip = false;

new Vue({
  render: (h) => h(App)
}).$mount("#app");

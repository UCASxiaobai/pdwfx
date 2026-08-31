<!-- vue2-done -->
<template>
  <section class="cet36-panel">
    <header v-if="title || labelIndex" class="cet36-panel__title" :class="{ 'cet36-panel__title--labeled': labelIndex }">
      <img
        v-if="labelIndex"
        class="cet36-panel__title-img"
        :src="labelSrc"
        alt=""
      />
      <h3 v-if="title" class="cet36-panel__title-text">{{ title }}</h3>
    </header>
    <div class="cet36-panel__body">
      <slot />
    </div>
  </section>
</template>

<script>
export default {
  name: "ThemePanel",
  props: {
    title: { type: String, default: "" },
    labelIndex: { type: Number, default: 0 }
  },
  computed: {
    labelSrc() {
      const n = this.labelIndex || 1;
      return require(`@/assets/cet36/img/home/label${n}.png`);
    }
  }
};
</script>

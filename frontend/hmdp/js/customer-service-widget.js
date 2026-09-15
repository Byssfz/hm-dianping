const customerServiceWidgetRoot = document.getElementById("customer-service-widget");
const customerServiceWidgetShopId = customerServiceWidgetRoot.dataset.shopId
  || (location.pathname.endsWith("/shop-detail.html") ? util.getUrlParam("id") : "");
const customerServiceWidgetStorageKey = "customerServiceWidgetState:" + (customerServiceWidgetShopId || "general");

if (!customerServiceWidgetRoot.innerHTML.trim()) {
  customerServiceWidgetRoot.innerHTML = `
    <button v-if="!open" class="customer-widget-launcher" type="button" @click="show">
      <span class="mark">马</span><span>智能客服</span>
    </button>
    <div v-if="open" class="customer-widget-layer" @click.self="close">
      <section class="customer-widget-sheet" aria-label="智能客服聊天窗口">
        <div class="customer-widget-handle"></div>
        <header class="customer-widget-header">
          <div class="customer-widget-avatar">马</div>
          <div class="customer-widget-title"><strong>点评小助手</strong><span><i></i>在线 · 在当前页面继续咨询</span></div>
          <div class="customer-widget-actions">
            <button type="button" title="新会话" :disabled="sending" @click="reset"><i class="el-icon-refresh"></i></button>
            <button type="button" title="打开完整页面" @click="expand"><i class="el-icon-full-screen"></i></button>
            <button type="button" title="关闭" @click="close"><i class="el-icon-close"></i></button>
          </div>
        </header>
        <div v-if="shopId" class="customer-widget-context">当前商铺咨询 · #{{ shopId }}</div>
        <div ref="messages" class="customer-widget-messages" aria-live="polite">
          <div v-for="(item,index) in messages" :key="index" class="customer-widget-message" :class="item.role">
            <div v-if="item.role === 'assistant'" class="customer-widget-mini-avatar">马</div>
            <div class="customer-widget-bubble">{{ item.content }}</div>
          </div>
          <div v-if="sending" class="customer-widget-message assistant">
            <div class="customer-widget-mini-avatar">马</div>
            <div class="customer-widget-bubble customer-widget-typing"><i></i><i></i><i></i></div>
          </div>
        </div>
        <div v-if="messages.length < 3" class="customer-widget-prompts">
          <button v-for="item in prompts" :key="item" type="button" :disabled="sending" @click="send(item)">{{ item }}</button>
        </div>
        <form class="customer-widget-composer" @submit.prevent="send(input)">
          <textarea v-model="input" rows="1" maxlength="2000" :disabled="sending" placeholder="边逛边问，页面不会离开…" @keydown.enter.exact.prevent="send(input)"></textarea>
          <button class="customer-widget-send" type="submit" :disabled="sending || !input.trim()"><i class="el-icon-position"></i></button>
        </form>
      </section>
    </div>`;
}

const customerServiceWidget = new Vue({
  el: "#customer-service-widget",
  data: {
    open: new URLSearchParams(location.search).get("customerServiceOpen") === "1",
    shopId: customerServiceWidgetShopId,
    input: "",
    sending: false,
    conversationId: "",
    messages: [{
      role: "assistant",
      content: customerServiceWidgetShopId
        ? "你好，我是点评小助手。已经关联当前商铺，你可以直接问营业时间、地址或优惠券。"
        : "你好，我是点评小助手。你可以边逛边问我商铺、优惠券或自己的订单。"
    }],
    prompts: customerServiceWidgetShopId
      ? ["这家店几点关门？", "这家店有哪些优惠券？", "这家店在哪里？"]
      : ["附近有什么热门店？", "帮我查最近的订单", "优惠券怎么使用？"]
  },
  created() {
    this.restore();
  },
  mounted() {
    window.addEventListener("hmdp:open-customer-service", this.show);
    if (this.open) {
      document.body.style.overflow = "hidden";
      this.scrollToBottom();
    }
  },
  beforeDestroy() {
    window.removeEventListener("hmdp:open-customer-service", this.show);
    document.body.style.overflow = "";
  },
  methods: {
    show() {
      this.open = true;
      document.body.style.overflow = "hidden";
      this.scrollToBottom();
    },
    close() {
      this.open = false;
      document.body.style.overflow = "";
    },
    expand() {
      location.href = "/customer-service.html" + (this.shopId ? "?shopId=" + encodeURIComponent(this.shopId) : "");
    },
    reset() {
      this.conversationId = "";
      this.messages = [{role: "assistant", content: this.shopId ? "新会话已开始。可以继续咨询当前商铺。" : "新会话已开始。今天想了解什么？"}];
      this.persist();
      this.scrollToBottom();
    },
    restore() {
      try {
        const saved = JSON.parse(sessionStorage.getItem(customerServiceWidgetStorageKey));
        if (!saved || !Array.isArray(saved.messages)) return;
        this.conversationId = typeof saved.conversationId === "string" ? saved.conversationId : "";
        this.messages = saved.messages.filter(item => item && typeof item.content === "string" && ["user", "assistant"].includes(item.role)).slice(-20);
      } catch (error) {
        sessionStorage.removeItem(customerServiceWidgetStorageKey);
      }
    },
    persist() {
      sessionStorage.setItem(customerServiceWidgetStorageKey, JSON.stringify({conversationId: this.conversationId, messages: this.messages.slice(-20)}));
    },
    scrollToBottom() {
      this.$nextTick(() => {
        const box = this.$refs.messages;
        if (box) box.scrollTop = box.scrollHeight;
      });
    },
    send(question) {
      const content = (question || "").trim();
      if (!content || this.sending) return;
      if (!token) {
        this.$message.error("请先登录");
        const returnPath = location.pathname + location.search;
        setTimeout(() => location.href = "/login.html?redirect=" + encodeURIComponent(returnPath), 500);
        return;
      }
      this.messages.push({role: "user", content});
      this.input = "";
      this.sending = true;
      this.persist();
      this.scrollToBottom();
      axios.post("/customer-service/chat", {
        conversationId: this.conversationId || "",
        shopId: this.shopId || "",
        message: content
      }, {timeout: 60000})
        .then(({data}) => {
          if (!data || typeof data.conversationId !== "string" || typeof data.reply !== "string") throw "客服返回数据格式异常，请稍后重试";
          this.conversationId = data.conversationId;
          this.messages.push({role: "assistant", content: data.reply});
          this.persist();
        })
        .catch(error => {
          const message = typeof error === "string" ? error : "客服服务暂时不可用，请稍后重试";
          if (message.includes("会话不存在") || message.includes("会话数据异常")) this.conversationId = "";
          this.messages.push({role: "assistant", content: message});
          this.persist();
        })
        .finally(() => {
          this.sending = false;
          this.scrollToBottom();
        });
    }
  }
});
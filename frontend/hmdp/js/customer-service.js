const shopId = util.getUrlParam("shopId");
const shopName = util.getUrlParam("shopName");
const storageKey = "customerServiceState:" + (shopId || "general");

const welcomeMessage = shopId
  ? "你好，我是点评小助手。当前已经关联这家商铺，你可以直接问营业时间、优惠券或其他门店信息。"
  : "你好，我是点评小助手。你可以问我商铺信息、优惠券规则，或者查询自己的订单。";

const app = new Vue({
  el: "#app",
  data: {
    token,
    shopId,
    shopName,
    input: "",
    sending: false,
    conversationId: "",
    messages: [],
    suggestions: shopId
      ? ["这家店几点关门？", "这家店有哪些优惠券？", "这家店在哪里？"]
      : ["附近有哪些热门商铺？", "帮我查询最近的订单", "优惠券有哪些使用规则？"]
  },
  computed: {
    showSuggestions() {
      return this.messages.length <= 2;
    }
  },
  created() {
    this.restoreState();
    if (!this.messages.length) this.messages = [{role: "assistant", content: welcomeMessage}];
  },
  mounted() {
    this.scrollToBottom();
    if (!this.token) this.messages.push({role: "assistant", content: "客服查询需要登录。登录后再回来，我会继续在这里等你。"});
  },
  methods: {
    goBack() {
      if (history.length > 1) history.back();
      else location.href = "/";
    },
    restoreState() {
      try {
        const saved = JSON.parse(sessionStorage.getItem(storageKey));
        if (!saved || !Array.isArray(saved.messages)) return;
        this.conversationId = typeof saved.conversationId === "string" ? saved.conversationId : "";
        this.messages = saved.messages.filter(item =>
          item && (item.role === "user" || item.role === "assistant") && typeof item.content === "string"
        ).slice(-30);
      } catch (error) {
        sessionStorage.removeItem(storageKey);
      }
    },
    saveState() {
      sessionStorage.setItem(storageKey, JSON.stringify({
        conversationId: this.conversationId,
        messages: this.messages.slice(-30).map(({role, content}) => ({role, content}))
      }));
    },
    scrollToBottom() {
      this.$nextTick(() => {
        const element = this.$refs.messages;
        if (element) element.scrollTop = element.scrollHeight;
      });
    },
    newConversation() {
      this.conversationId = "";
      this.messages = [{role: "assistant", content: welcomeMessage}];
      this.input = "";
      this.saveState();
      this.scrollToBottom();
    },
    clearShopContext() {
      location.href = "/customer-service.html";
    },
    retry(question) {
      this.send(question, true);
    },
    send(question, isRetry) {
      const content = (question || "").trim();
      if (!content || this.sending) return;
      if (!this.token) {
        this.$message.error("请先登录");
        setTimeout(() => {
          location.href = "/login.html?redirect=" + encodeURIComponent(location.pathname + location.search);
        }, 500);
        return;
      }

      if (!isRetry) this.messages.push({role: "user", content});
      this.input = "";
      this.sending = true;
      this.saveState();
      this.scrollToBottom();

      axios.post("/customer-service/chat", {
        conversationId: this.conversationId || "",
        shopId: this.shopId || "",
        message: content
      }, {timeout: 60000})
        .then(({data}) => {
          if (!data || typeof data.conversationId !== "string" || typeof data.reply !== "string") {
            throw "客服返回数据格式异常，请稍后重试";
          }
          this.conversationId = data.conversationId;
          this.messages.push({role: "assistant", content: data.reply});
          this.saveState();
        })
        .catch(error => {
          const message = typeof error === "string" ? error : "客服服务暂时不可用，请稍后重试";
          if (message.includes("会话不存在") || message.includes("会话数据异常")) this.conversationId = "";
          this.messages.push({role: "assistant", content: message, retry: content});
          this.saveState();
        })
        .finally(() => {
          this.sending = false;
          this.scrollToBottom();
        });
    }
  }
});
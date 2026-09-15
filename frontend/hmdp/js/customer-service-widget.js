const customerServiceWidget = new Vue({
  el: "#customer-service-widget",
  data: {
    open: new URLSearchParams(location.search).get("open") === "1",
    input: "",
    sending: false,
    conversationId: "",
    messages: [{role: "assistant", content: "你好，我是点评小助手。你可以边逛边问我商铺、优惠券或自己的订单。"}],
    prompts: ["附近有什么热门店？", "帮我查最近的订单", "优惠券怎么使用？"]
  },
  created() {
    this.restore();
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
      location.href = "/customer-service.html";
    },
    reset() {
      this.conversationId = "";
      this.messages = [{role: "assistant", content: "新会话已开始。今天想了解什么？"}];
      this.persist();
      this.scrollToBottom();
    },
    restore() {
      try {
        const saved = JSON.parse(sessionStorage.getItem("customerServiceWidgetState"));
        if (!saved || !Array.isArray(saved.messages)) return;
        this.conversationId = typeof saved.conversationId === "string" ? saved.conversationId : "";
        this.messages = saved.messages.filter(item => item && typeof item.content === "string" && ["user", "assistant"].includes(item.role)).slice(-20);
      } catch (error) {
        sessionStorage.removeItem("customerServiceWidgetState");
      }
    },
    persist() {
      sessionStorage.setItem("customerServiceWidgetState", JSON.stringify({
        conversationId: this.conversationId,
        messages: this.messages.slice(-20)
      }));
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
        shopId: "",
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
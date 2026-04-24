<template>
  <div class="agent-chat-panel">
    <div class="message-list" ref="messageListRef">
      <div v-if="messages.length === 0" class="empty-hint">
        在这里告诉小助理如何修改测试点或用例
      </div>
      <div v-for="(msg, idx) in messages" :key="idx" :class="['message', msg.role]">
        <div class="avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble" v-html="renderContent(msg.content)"></div>
      </div>
      <div v-if="loading" class="message assistant">
        <div class="avatar">AI</div>
        <div class="bubble typing">
          <span class="dot-animation">
            <span>.</span><span>.</span><span>.</span>
          </span>
          思考中
        </div>
      </div>
    </div>

    <div class="input-area">
      <el-input
        v-model="inputMessage"
        type="textarea"
        :rows="3"
        :autosize="{ minRows: 2, maxRows: 6 }"
        placeholder="输入消息，Enter 发送，Shift+Enter 换行"
        @keydown="handleKeydown"
        :disabled="loading"
        resize="none"
      />
      <el-button type="primary" @click="handleSend" :loading="loading" class="send-btn">
        发送
      </el-button>
    </div>
  </div>
</template>

<script>
import { ref, watch, nextTick } from 'vue'
import { marked } from 'marked'

export default {
  name: 'AgentChatPanel',
  props: {
    messages: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  emits: ['send'],
  setup(props, { emit }) {
    const inputMessage = ref('')
    const messageListRef = ref(null)

    function handleKeydown(e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        handleSend()
      }
    }

    function handleSend() {
      const msg = inputMessage.value.trim()
      if (!msg) return
      emit('send', msg)
      inputMessage.value = ''
    }

    function renderContent(text) {
      if (!text) return ''
      return marked(text)
    }

    function scrollToBottom() {
      nextTick(() => {
        if (messageListRef.value) {
          messageListRef.value.scrollTop = messageListRef.value.scrollHeight
        }
      })
    }

    watch(() => props.messages.length, scrollToBottom)

    return { inputMessage, messageListRef, handleKeydown, handleSend, renderContent }
  }
}
</script>

<style scoped>
.agent-chat-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}
.empty-hint {
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
  margin-top: 40%;
}
.message {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}
.message.user {
  flex-direction: row-reverse;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #fff;
  flex-shrink: 0;
}
.message.user .avatar { background: #409eff; }
.message.assistant .avatar { background: #67c23a; }
.bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
.message.user .bubble { background: #409eff; color: #fff; border-radius: 8px 2px 8px 8px; }
.message.assistant .bubble { background: #fff; border: 1px solid #e6e6e6; border-radius: 2px 8px 8px 8px; }
.typing { color: #999; font-style: italic; }
.dot-animation span {
  animation: blink 1.4s infinite both;
}
.dot-animation span:nth-child(2) { animation-delay: 0.2s; }
.dot-animation span:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink {
  0%, 80%, 100% { opacity: 0; }
  40% { opacity: 1; }
}
.input-area {
  padding: 12px;
  border-top: 1px solid #e6e6e6;
  background: #fff;
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.send-btn { height: 60px; }
</style>

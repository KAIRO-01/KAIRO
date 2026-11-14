            override fun onError(utteranceId: String?) {
                handler.post {
                    if (utteranceId == curUtt) {
                        speaking = false
                        afterSpeech()
                    }
                }
            }
        })
        speaking = true
        speakStart = SystemClock.uptimeMillis()
        lastActivity = speakStart
        val r = engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            return false
        }
        return true
    }

    private fun afterSpeech() {
        if (!running) return
        if (listenAfter) {
            listenAfter = false
            startListening()
        } else {
            refresh()
        }
    }

    private fun stopSpeaking() {
        curUtt = ""
        tts?.stop()
        speaking = false
        listenAfter = false
    }

    // ---------- Chat ----------

    private fun afterReply(reply: String, speakIt: Boolean) {
        var spoke = false
        if (speakIt) {
            listenAfter = session
            spoke = speak(reply)
        }
        if (!spoke) {
            listenAfter = false
            if (session && !listening) {
                startListening()
                return
            }
        }
        refresh()
    }

    private fun localReply(text: String, reply: String, fromVoice: Boolean) {
        addBubble("user", text)
        addBubble("assistant", reply)
        note = null
        afterReply(reply, fromVoice)
    }

    private fun sendMessage(text: String, fromVoice: Boolean) {
        if (busy) return
        if (listening) {
            recognizer?.destroy()
            recognizer = null
            listening = false
        }

        val lower = text.lowercase(Locale.ROOT)
        if (forgetWords.any { lower.contains(it) }) {
            history.clear()
            saveHistory()
            localReply(text, "Theek hai, purani baatein bhool gaya.", fromVoice)
            return
        }

        val phoneReply = PhoneActions.handle(this, text, ui != null)
        if (phoneReply != null) {
            localReply(text, phoneReply, fromVoice)
            return
        }

        val prefs = getSharedPreferences("kairo", Context.MODE_PRIVATE)
        val token = (prefs.getString("token", "") ?: "").trim()
        if (token.isBlank()) {
            note = "Pehle Settings (gear) mein KAIRO_TOKEN daalo"
            session = false
            refresh()
            return
        }
        val base = (prefs.getString("url", MainActivity.DEFAULT_URL) ?: MainActivity.DEFAULT_URL)
            .ifBlank { MainActivity.DEFAULT_URL }

        stopSpeaking()
        note = null
        history.add("user" to text)
        trimHistory()
        saveHistory()
        addBubble("user", text)
        busy = true
        refresh()

        val snapshot = history.toMutableList()
        if (PhoneState.wanted(text) && snapshot.isNotEmpty()) {
            val last = snapshot.removeAt(snapshot.size - 1)
            snapshot.add(last.first to (PhoneState.describe(this) + "\n" + last.second))
        }

        thread {
            val result = try {
                BackendClient.chat(base, token, snapshot)
            } catch (e: Exception) {
                Pair(false, "Network error (" + e.javaClass.simpleName + "). Internet check karo aur dobara try karo.")
            }
            handler.post {
                if (!running) return@post
                if (result.first) {
                    history.add("assistant" to result.second)
                    trimHistory()
                } else if (history.isNotEmpty()) {
                    history.removeAt(history.size - 1)
                }
                saveHistory()
                addBubble("assistant", result.second)
                busy = false
                afterReply(result.second, result.first && fromVoice)
            }
        }
    }
}

package io.islandbitcoin.foxy.bridge

import io.islandbitcoin.foxy.network.InboxAnswer
import java.text.BreakIterator
import java.util.Locale

/**
 * The one table of answers the page owes for payments it was handed, by key
 * (FoxyBridge.swift `inboxAnswers` and `tapAnswerKeys` at 8df0bb1). A payment
 * posted to an onion address ([DeliveryBridge], ENG-672) and one carried over
 * the tap link ([TapBridge], ENG-670) wait here alike, each reaching the page as
 * `_requestPaid(body, key[, "tap"])`, and `inboxAnswer` takes either out, once
 * (FoxyBridge+Delivery.swift `handleInboxAnswer`): the status clamped to 200,
 * 409 or 422, the text cut to 300 characters, and the payer's JSON built as an
 * iPhone builds it. A tap answer reads the reason back out of that JSON
 * (FoxyBridge+Tap.swift 103-111).
 *
 * The keys of tap payments are remembered apart, because they outlive Foxy
 * being put away and an onion's do not ([dropOnion],
 * FoxyBridge+Delivery.swift `closeInboxForBackground`): the payer deletes its
 * only copy of a tap payment on this answer, and the link is still up.
 *
 * Main thread, but for [handle]'s reading of the body.
 */
class InboxAnswers(
    private val host: PageHost,
    private val resolve: (id: String, text: String?, error: String?) -> Unit,
) {
    companion object {
        const val ACTION = "inboxAnswer"

        const val NO_PAYMENT_WAITING = "No payment is waiting on that answer."

        /** The longest text a payer is told, in characters as a person reads them. */
        const val MOST_TEXT = 300

        /**
         * A string as Apple's JSONSerialization writes it, so the payer reads
         * the bytes an iPhone receiver sends: `"`, `\` and `/` escaped (Apple
         * escapes the slash), \b \f \n \r \t by name, other controls as \u00xx,
         * everything else as it is.
         */
        fun appleJsonString(value: String): String {
            val out = StringBuilder(value.length + 2).append('"')
            for (c in value) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '/' -> out.append("\\/")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            return out.append('"').toString()
        }

        /** What the payer hears for the page's word (FoxyBridge+Delivery.swift `handleInboxAnswer`). */
        fun answerBody(status: Int, text: String): String =
            if (status == 200) "{\"ok\":true}" else "{\"error\":" + appleJsonString(text) + "}"

        /** Only the statuses a payer is told: 200, 409, 422; anything else is 422. */
        fun answerStatus(asked: Any?): Int {
            val n = (asked as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toInt() ?: 422
            return if (n == 200 || n == 409 || n == 422) n else 422
        }

        /** The first `most` characters as a person sees them (Swift's `prefix` counts grapheme clusters). */
        fun prefixCharacters(text: String, most: Int): String {
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(text)
            var end = 0
            for (i in 0 until most) {
                val next = it.next()
                if (next == BreakIterator.DONE) return text
                end = next
            }
            return text.substring(0, end)
        }

        /** The reason in a payer's JSON, `{"error": text}`, or "" (FoxyBridge+Tap.swift 107-110). */
        fun reason(said: String): String = try {
            (Json.parse(said) as? Map<*, *>)?.get("error") as? String ?: ""
        } catch (e: Json.Malformed) {
            ""
        }
    }

    private val answers = HashMap<String, InboxAnswer>()

    /** Which of [answers] are for a payment that came over the tap link and not to an onion address. */
    private val tapKeys = HashSet<String>()

    /** A payment to an onion address waits on the page under [key]. */
    fun onion(key: String, answer: InboxAnswer) {
        answers[key] = answer
    }

    /** A payment over the tap link waits on the page under [key]; [tell] hears the status and the reason alone. */
    fun tap(key: String, tell: (status: Int, why: String) -> Unit) {
        tapKeys.add(key)
        answers[key] = { status, said ->
            tapKeys.remove(key)
            tell(status, reason(said))
        }
    }

    /** The key is no longer answered: its payer was answered on its own, or gave up. */
    fun drop(key: String) {
        answers.remove(key)
        tapKeys.remove(key)
    }

    /** Whether an answer is waiting on [key] (the tests). */
    fun waiting(key: String): Boolean = key in answers

    /** Foxy is put away: the onion's answers go with its addresses, a tap's stay (`closeInboxForBackground`). */
    fun dropOnion() {
        answers.keys.retainAll(tapKeys)
    }

    /** The page went: nothing is left for it to answer. */
    fun clear() {
        answers.clear()
        tapKeys.clear()
    }

    /** `inboxAnswer`: {answer, status, text}. */
    fun handle(id: String, body: Map<String, Any?>) {
        val key = body["answer"] as? String
        val status = answerStatus(body["status"])
        val text = prefixCharacters(body["text"] as? String ?: "", MOST_TEXT)
        host.post {
            val answer = key?.let { answers.remove(it) } ?: return@post resolve(id, null, NO_PAYMENT_WAITING)
            tapKeys.remove(key)
            answer(status, answerBody(status, text))
            resolve(id, "ok", null)
        }
    }
}

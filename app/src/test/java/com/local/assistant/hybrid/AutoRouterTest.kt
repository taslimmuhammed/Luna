package com.local.assistant.hybrid

import com.local.assistant.hybrid.AutoRouter.Lane
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRouterTest {

    private val names = listOf("Priya Sharma", "Rahul", "Anjali")

    private fun lane(text: String, signals: AutoRouter.Signals = AutoRouter.Signals(), lastWasAsk: Boolean = false, attachment: Boolean = false) =
        AutoRouter.route(AutoRouter.Input(text, hasAttachment = attachment, lastWasAsk = lastWasAsk, knownNames = names), signals)

    private fun check(expected: Lane, text: String, reason: RouteReason? = null, signals: AutoRouter.Signals = AutoRouter.Signals(), lastWasAsk: Boolean = false) {
        val route = lane(text, signals, lastWasAsk)
        assertEquals("lane for \"$text\"", expected, route.lane)
        if (reason != null) assertEquals("reason for \"$text\"", reason, route.reason)
    }

    @Test fun nothingLeavesWhenItCant() {
        check(Lane.LOCAL_ONLY, "write an essay about the monsoon", RouteReason.OFFLINE, AutoRouter.Signals(online = false))
        check(Lane.LOCAL_ONLY, "write an essay about the monsoon", RouteReason.NOT_SET_UP, AutoRouter.Signals(cloudReady = false))
        check(Lane.LOCAL_ONLY, "write an essay about the monsoon", RouteReason.ALLOWANCE, AutoRouter.Signals(asksLeft = 0))
        assertEquals(Lane.LOCAL_ONLY, AutoRouter.route(AutoRouter.Input("latest news", locked = true), AutoRouter.Signals()).lane)
    }

    @Test fun idNumbersStayOnThePhone() {
        check(Lane.LOCAL_ONLY, "my aadhaar is 2341 2341 2346, write a letter to the bank", RouteReason.SENSITIVE)
        check(Lane.LOCAL_ONLY, "my card 4111 1111 1111 1111 was charged twice, draft an email", RouteReason.SENSITIVE)
    }

    @Test fun personalThingsAreThePhones() {
        listOf(
            "show me photos from Munnar",
            "what did Rahul text me",
            "how many steps did I walk today",
            "what's on my calendar tomorrow",
            "summarise my rental agreement pdf",
            "remind me to buy milk at 6",
            "set an alarm for 5 am",
            "call amma",
            "what's my wife's birthday",
            "do you remember what I said about Goa",
            "suggest a restaurant near me",
            "write a poem for Anjali",
            "plan Priya's birthday dinner",
            "hi luna how are you",
            "thanks",
        ).forEach { check(Lane.LOCAL_CAN_ASK, it) }
        assertEquals(Lane.LOCAL_CAN_ASK, lane("what does this say", attachment = true).lane)
    }

    @Test fun cloudWorthyGoesFirst() {
        check(Lane.ASK_FIRST, "Write a 200-word Instagram caption about Munnar tea gardens", RouteReason.WRITING)
        check(Lane.ASK_FIRST, "Hey Luna, draft an email to my manager asking for two days of leave", RouteReason.WRITING)
        check(Lane.ASK_FIRST, "write a python function to reverse a linked list", RouteReason.WRITING)
        check(Lane.ASK_FIRST, "translate good morning, how are you to Tamil", RouteReason.TRANSLATE)
        check(Lane.ASK_FIRST, "what's the latest on the India vs Australia match", RouteReason.LIVE)
        check(Lane.ASK_FIRST, "what's the weather in Kochi this weekend", RouteReason.LIVE)
        check(Lane.ASK_FIRST, "plan a 3 day itinerary for Coorg", RouteReason.PLANNING)
        check(Lane.ASK_FIRST, "compare the Pixel 10 and the Galaxy S26", RouteReason.PLANNING)
    }

    @Test fun leaningOnEarlierTurnsIsThePhonesToWord() {
        check(Lane.LOCAL_CAN_ASK, "write a poem about it")
        check(Lane.LOCAL_CAN_ASK, "what's the weather there")
        check(Lane.LOCAL_CAN_ASK, "write a message to Priya saying I'm late")
    }

    @Test fun followUpsGoBackToGemini() {
        check(Lane.ASK_FIRST, "make it shorter", RouteReason.FOLLOW_UP, lastWasAsk = true)
        check(Lane.ASK_FIRST, "now in Hindi", RouteReason.FOLLOW_UP, lastWasAsk = true)
        check(Lane.LOCAL_CAN_ASK, "make it shorter")
        // A command after an answer is still the phone's.
        check(Lane.LOCAL_CAN_ASK, "remind me to post it at 7", lastWasAsk = true)
    }

    @Test fun aHotPhoneSendsExplanations() {
        check(Lane.LOCAL_CAN_ASK, "explain how UPI works")
        check(Lane.ASK_FIRST, "explain how UPI works", RouteReason.SAVE_BATTERY, AutoRouter.Signals(hot = true))
    }
}

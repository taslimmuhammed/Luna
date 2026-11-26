package com.local.assistant.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeilTest {

    @Test fun questionsGoAsWritten() {
        val question = "Write a birthday wish for Priya from Taslim; she loves Kochi"
        val sent = Veil.withhold(question)
        assertEquals(question, sent.text)
        assertEquals(0, sent.withheld)
        assertNull(Veil.withheldNote(sent.withheld))
    }

    @Test fun idsAreWithheld() {
        val sent = Veil.withhold("My Aadhaar 2341 2341 2346 needs updating")
        assertTrue(sent.text.contains(Veil.WITHHELD))
        assertFalse(sent.text.contains("2341"))
        assertEquals("1 ID or card number withheld", Veil.withheldNote(sent.withheld))
    }

    @Test fun amountsAndDatesAreKept() {
        val question = "Rent goes from ₹22,000 to ₹25,000 on 1 January 2027"
        assertEquals(question, Veil.withhold(question).text)
    }

    @Test fun namesAreFoundForTheRouter() {
        val people = listOf("Priya Sharma", "Rahul", "Will")
        assertTrue(Veil.mentions("plan Priya's birthday dinner", people))
        assertTrue(Veil.mentions("gift ideas for rahul", people))
        // An everyday word is not a name.
        assertFalse(Veil.mentions("Will it rain in Kochi tomorrow?", people))
    }

    @Test fun aPastedMessageIsCaught() {
        val sms = "Dear tenant, as per the agreement the monthly rent will be revised to Rs 25000 from January, kindly confirm by Friday."
        assertTrue(Veil.pastes("My landlord wrote: as per the agreement the monthly rent will be revised to Rs 25000 from January. What are my rights?", listOf(sms)))
        assertFalse(Veil.pastes("My landlord is raising rent from 22000 to 25000 in January. What are my rights as a tenant in Karnataka?", listOf(sms)))
    }
}

package bhupendra.morphy.core

import bhupendra.morphy.core.text.NameMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchTest {
    private fun matches(q: String, name: String) = NameMatch.score(q, name) >= 0.6

    @Test fun `spoken spellings match saved names`() {
        assertTrue(matches("Poorab", "Purab"))
        assertTrue(matches("Purav", "Purab Sharma"))
        assertTrue(matches("Snehaa", "Sneha"))
        assertTrue(matches("Aashish", "Ashish Kumar"))
        assertTrue(matches("Rishi", "Rishi Dwivedi"))
        assertTrue(matches("maa", "Maa"))
    }

    @Test fun `different people don't match`() {
        assertTrue(!matches("Purab", "Pranav"))
        assertTrue(!matches("Rishi", "Rahul"))
        assertTrue(!matches("Neha", "Sneha Gupta"))
    }

    @Test fun `exact beats fuzzy`() {
        assertTrue(NameMatch.score("Purab", "Purab") > NameMatch.score("Poorab", "Pranav Purohit"))
        assertEquals(1.0, NameMatch.score("Purab", "Purab"), 0.0)
    }
}

package app.terra.explore
import org.junit.Test
import org.junit.Assert.*
class AccountTests {
    @Test fun passwordDerivationMatchesIndependentVector() {
        val key=LocalAccount.derive("password","salt".toByteArray())
        assertEquals("9d5f68774306eaee6c79c5b4d3a263907f81c55d4daa1c50585e1e849065e090",key.joinToString("") { "%02x".format(it) })
        assertFalse(key.contentEquals(LocalAccount.derive("wrong-password","salt".toByteArray())))
        assertFalse(key.contentEquals(LocalAccount.derive("password","different-salt".toByteArray())))
    }
}

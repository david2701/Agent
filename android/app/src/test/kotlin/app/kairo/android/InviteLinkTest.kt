package app.kairo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    @Test
    fun `canonical join link`() {
        val link = InviteLink.parse("https://kairo.example.com/join?code=abcd2345")
        assertEquals(InviteLink("https://kairo.example.com", "ABCD-2345"), link)
    }

    @Test
    fun `legacy unirse link still works`() {
        val link = InviteLink.parse("https://kairo.example.com/unirse?codigo=ABCD-2345")
        assertEquals(InviteLink("https://kairo.example.com", "ABCD-2345"), link)
    }

    @Test
    fun `either query parameter name is accepted on either path`() {
        assertEquals("ABCD-2345", InviteLink.parse("https://h.example/join?codigo=ABCD-2345")?.code)
        assertEquals("ABCD-2345", InviteLink.parse("https://h.example/unirse?code=ABCD-2345")?.code)
    }

    @Test
    fun `port is kept and extra parameters are ignored`() {
        val link = InviteLink.parse("http://192.168.1.20:8080/join/?utm=x&code=wxyz%2D9876")
        assertEquals(InviteLink("http://192.168.1.20:8080", "WXYZ-9876"), link)
    }

    @Test
    fun `other links are not invites`() {
        assertNull(InviteLink.parse(null))
        assertNull(InviteLink.parse(""))
        assertNull(InviteLink.parse("https://kairo.example.com/today"))
        assertNull(InviteLink.parse("https://kairo.example.com/join"))
        assertNull(InviteLink.parse("https://kairo.example.com/join?code=short"))
        assertNull(InviteLink.parse("not a url"))
    }
}

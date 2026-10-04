package de.doetchen.projects.proxytools.chat

import de.doetchen.projects.proxytools.core.Permissions
import de.doetchen.projects.proxytools.core.ProxyToolsCore
import de.doetchen.projects.proxytools.testing.CoreTestBase
import de.doetchen.projects.proxytools.testing.FakeActor
import de.doetchen.projects.proxytools.testing.FakePlayer
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MessageTest : CoreTestBase() {
    @Test
    fun `msg delivers to the target and reply works back and forth`() {
        val core = core()
        val alice = chatter("Alice")
        val bob = chatter("Bob")

        core.commands.msg(alice.actor, listOf("bob", "hi", "there"))
        assertTrue(alice.actor.messages.single().contains("hi there"))
        assertTrue(bob.player.received.single().contains("Alice") && bob.player.received.single().contains("hi there"))

        core.commands.reply(bob.actor, listOf("hello", "back"))
        assertTrue(alice.player.received.single().contains("hello back"))

        core.commands.reply(alice.actor, listOf("and", "again"))
        assertTrue(bob.player.received.last().contains("and again"))
        assertEquals(2, bob.player.received.size)
    }

    @Test
    fun `reply follows the latest conversation partner`() {
        val core = core()
        val alice = chatter("Alice")
        val bob = chatter("Bob")
        val carol = chatter("Carol")

        core.commands.msg(alice.actor, listOf("Bob", "first"))
        core.commands.msg(carol.actor, listOf("Alice", "second"))
        core.commands.reply(alice.actor, listOf("to carol"))
        assertTrue(carol.player.received.last().contains("to carol"))
        assertEquals(1, bob.player.received.size)
    }

    @Test
    fun `msg and reply handle unknown targets, nobody to reply to, self and offline partners`() {
        val core = core()
        val alice = chatter("Alice")
        val bob = chatter("Bob")

        core.commands.msg(alice.actor, listOf("Nobody", "hi"))
        assertTrue(alice.actor.messages.last().contains("not online"))
        core.commands.msg(alice.actor, listOf("alice", "hi"))
        assertTrue(alice.actor.messages.last().contains("yourself"))
        core.commands.msg(alice.actor, listOf("Bob"))
        assertTrue(alice.actor.messages.last().contains("/msg"))
        core.commands.reply(alice.actor, listOf("hi"))
        assertTrue(alice.actor.messages.last().contains("nobody"))

        core.commands.msg(alice.actor, listOf("Bob", "hi"))
        platform.players.remove(bob.player)
        core.commands.reply(alice.actor, listOf("still there?"))
        assertTrue(alice.actor.messages.last().contains("no longer online"))

        val console = FakeActor(uniqueId = null)
        core.commands.msg(console, listOf("Alice", "hi"))
        assertTrue(console.messages.single().contains("players"))
    }

    @Test
    fun `colour codes in a private message are shown as typed`() {
        val core = core()
        val alice = chatter("Alice")
        val bob = chatter("Bob")
        core.commands.msg(alice.actor, listOf("Bob", "&cred", "§kfake"))
        val received = bob.player.received.single()
        assertTrue(received.contains("&cred"))
        assertFalse(received.contains("§k"))
    }

    @Test
    fun `social spy is off until enabled, needs the permission and skips the two participants`() {
        val core = core()
        val alice = chatter("Alice")
        val bob = chatter("Bob")
        val spy = chatter("Spy", setOf(Permissions.SOCIALSPY))
        val other = chatter("Other")

        core.commands.msg(alice.actor, listOf("Bob", "secret"))
        assertTrue(spy.player.received.isEmpty())

        core.commands.socialSpy(other.actor, emptyList())
        assertTrue(other.actor.messages.single().contains("permission"))

        core.commands.socialSpy(spy.actor, emptyList())
        assertTrue(spy.actor.messages.last().contains("enabled"))
        core.commands.msg(alice.actor, listOf("Bob", "secret"))
        assertTrue(spy.player.received.single().contains("secret"))
        assertEquals(2, bob.player.received.size)

        core.commands.socialSpy(spy.actor, listOf("off"))
        core.commands.msg(alice.actor, listOf("Bob", "again"))
        assertEquals(1, spy.player.received.size)

        core.commands.socialSpy(spy.actor, listOf("maybe"))
        assertTrue(spy.actor.messages.last().contains("/socialspy"))
    }

    @Test
    fun `a spy who takes part in the conversation does not see it twice and private messages can be switched off`() {
        val core = core()
        val spy = chatter("Spy", setOf(Permissions.SOCIALSPY))
        val bob = chatter("Bob")
        core.commands.socialSpy(spy.actor, listOf("on"))
        core.commands.msg(spy.actor, listOf("Bob", "hi"))
        assertTrue(spy.player.received.isEmpty())

        Files.writeString(folder.resolve("config.yml"), "msg:\n  enabled: false\n")
        core.reload()
        core.commands.msg(spy.actor, listOf("Bob", "hi"))
        assertTrue(spy.actor.messages.last().contains("disabled"))
        assertEquals(1, bob.player.received.size)
    }

    @Test
    fun `msg tab completion suggests online players but not yourself`() {
        val core = core()
        val alice = chatter("Alice")
        chatter("Bob")
        chatter("Bert")
        assertEquals(listOf("Bob", "Bert"), core.commands.suggestMsg(alice.actor, listOf("b")))
        assertTrue(core.commands.suggestMsg(alice.actor, listOf("Bob", "x")).isEmpty())
    }

    @Test
    fun `ignoring a player blocks their messages, can be undone and survives a restart`() {
        val core = core()
        val alice = chatter("Alice")
        val bob = chatter("Bob")

        core.commands.ignore(bob.actor, listOf("alice"))
        assertTrue(bob.actor.messages.last().contains("ignore"))
        core.commands.msg(alice.actor, listOf("Bob", "hello"))
        assertTrue(bob.player.received.isEmpty())
        assertTrue(alice.actor.messages.last().contains("not accepting"))

        core.commands.msg(bob.actor, listOf("Alice", "i can write"))
        assertEquals(1, alice.player.received.size)

        core.commands.ignore(bob.actor, listOf("list"))
        assertTrue(bob.actor.messages.last().contains("Alice"))

        core.shutdown()
        val restarted = ProxyToolsCore(platform, releases)
        restarted.commands.msg(alice.actor, listOf("Bob", "hello again"))
        assertTrue(bob.player.received.isEmpty(), "the ignore list is restored")

        platform.players.remove(alice.player)
        restarted.commands.ignore(bob.actor, listOf("alice"))
        assertTrue(bob.actor.messages.last().contains("no longer"), "unignoring works by stored name while offline")
        platform.players += alice.player
        restarted.commands.msg(alice.actor, listOf("Bob", "welcome back"))
        assertEquals(1, bob.player.received.size)
    }

    @Test
    fun `msgtoggle blocks everybody except staff with the bypass permission`() {
        val core = core()
        val alice = chatter("Alice")
        val staff = chatter("Staff", setOf(Permissions.MSG_BYPASS))
        val bob = chatter("Bob")

        core.commands.msgToggle(bob.actor, emptyList())
        assertTrue(bob.actor.messages.last().contains("disabled"))
        core.commands.msg(alice.actor, listOf("Bob", "hi"))
        assertTrue(bob.player.received.isEmpty())
        core.commands.msg(staff.actor, listOf("Bob", "official"))
        assertEquals(1, bob.player.received.size)

        core.commands.msgToggle(bob.actor, emptyList())
        core.commands.msg(alice.actor, listOf("Bob", "hi"))
        assertEquals(2, bob.player.received.size)
    }

    @Test
    fun `ignore handles console, yourself and unknown players`() {
        val core = core()
        val alice = chatter("Alice")
        val console = FakeActor(uniqueId = null)
        core.commands.ignore(console, listOf("Alice"))
        assertTrue(console.messages.single().contains("players"))
        core.commands.ignore(alice.actor, listOf("Alice"))
        assertTrue(alice.actor.messages.last().contains("yourself"))
        core.commands.ignore(alice.actor, listOf("Nobody"))
        assertTrue(alice.actor.messages.last().contains("not online"))
        core.commands.ignore(alice.actor, emptyList())
        assertTrue(alice.actor.messages.last().contains("/ignore"))
    }

    @Test
    fun `a player who left no longer keeps a reply partner`() {
        val core = core()
        val aliceId = UUID.randomUUID()
        val bobId = UUID.randomUUID()
        val alice = FakePlayer("Alice", uniqueId = aliceId)
        val bob = FakePlayer("Bob", uniqueId = bobId)
        platform.players += listOf(alice, bob)
        val aliceActor = FakeActor(name = "Alice", uniqueId = aliceId)
        val bobActor = FakeActor(name = "Bob", uniqueId = bobId)

        core.commands.msg(aliceActor, listOf("Bob", "hi"))
        assertTrue(core.privateMessages.hasPartner(bobActor))
        core.playerLeft(bob)
        platform.players.remove(bob)
        assertFalse(core.privateMessages.hasPartner(bobActor))
        core.commands.reply(aliceActor, listOf("still there?"))
        assertTrue(aliceActor.messages.last().contains("no longer online"))
    }
}

package com.app.nuri.bluetooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class BtSlotManagerTest {
    @Test fun mruOrderingAndLruEviction() {
        val m = BtSlotManager(capacity = 3)
        m.touch("a","A",1); m.touch("b","B",2); m.touch("c","C",3)
        assertEquals(listOf("c","b","a"), m.listMruFirst().map { it.address })
        m.touch("a","A",4); assertEquals("a", m.listMruFirst().first().address)
        val evicted = m.touch("d","D",5); assertEquals("b", evicted?.address); assertEquals(3, m.listMruFirst().size)
    }
    @Test fun encodeDecodeRoundTrip() {
        val m = BtSlotManager(); m.touch("aa:bb","Kitchen speaker",99)
        val back = BtSlotManager.decode(m.encode()); assertEquals(1, back.listMruFirst().size); assertTrue(back.listMruFirst()[0].name.contains("Kitchen"))
    }
    @Test fun removeWorks() { val m = BtSlotManager(); m.touch("x","X",1); assertTrue(m.remove("x")); assertEquals(0, m.listMruFirst().size) }
}

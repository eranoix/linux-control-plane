package com.vpsmanager.data.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What these tests protect: the home screen widget shows a number that can be
 * half an hour old, because Android does not accept updating it any faster
 * than that. The age sentence is what separates "honest summary" from "a lie
 * on the home screen" — and the home screen is seen far more times a day than
 * the app.
 */
class ResumoDoServidorTest {

    private val agora = 1_700_000_000_000L

    /**
     * BEFORE THE FIRST READING the sentence cannot be "now".
     *
     * `medidoEm = 0` is the state of a freshly installed widget, before the app
     * has ever opened. If it said "now" next to three dashes, it would be
     * claiming to have measured what it never measured.
     */
    @Test
    fun `sem leitura nenhuma a frase diz isso, e nao agora`() {
        assertEquals("sem leitura ainda", idadeEmPalavras(0L, agora))
    }

    @Test
    fun `abaixo de um minuto e agora`() {
        assertEquals("agora", idadeEmPalavras(agora - 30_000L, agora))
    }

    @Test
    fun `minutos ate uma hora`() {
        assertEquals("há 1 min", idadeEmPalavras(agora - 60_000L, agora))
        assertEquals("há 45 min", idadeEmPalavras(agora - 45 * 60_000L, agora))
    }

    /**
     * Half an hour is the widget's REAL refresh interval, so this is the
     * sentence it shows most of the time. It has to be right.
     */
    @Test
    fun `meia hora — o intervalo real do widget — aparece em minutos`() {
        assertEquals("há 30 min", idadeEmPalavras(agora - 30 * 60_000L, agora))
    }

    @Test
    fun `horas depois de uma hora`() {
        assertEquals("há 1 h", idadeEmPalavras(agora - 60 * 60_000L, agora))
        assertEquals("há 5 h", idadeEmPalavras(agora - 5 * 60 * 60_000L, agora))
    }

    /**
     * Past a day the sentence stops counting. The exact number of days changes
     * no decision — what matters is that the data is no longer good.
     */
    @Test
    fun `acima de um dia para de contar`() {
        assertEquals("há mais de um dia", idadeEmPalavras(agora - 25L * 60 * 60_000L, agora))
        assertEquals("há mais de um dia", idadeEmPalavras(agora - 40L * 24 * 60 * 60_000L, agora))
    }

    /**
     * A device clock can walk backwards (a time zone adjustment, NTP). A
     * negative difference must not turn into "-3 min ago", which is the most
     * disconcerting sentence possible on an operations dashboard.
     */
    @Test
    fun `relogio para tras nao produz idade negativa`() {
        assertEquals("agora", idadeEmPalavras(agora + 60_000L, agora))
    }

    @Test
    fun `o resumo vazio mostra traco, nunca zero`() {
        val vazio = ResumoDoServidor.VAZIO
        assertEquals("—", vazio.cpu)
        assertEquals("—", vazio.memoria)
        assertEquals("—", vazio.disco)
        assertEquals(null, vazio.alerta)
    }
}

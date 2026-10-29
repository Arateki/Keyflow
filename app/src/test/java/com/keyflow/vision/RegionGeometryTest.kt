package com.keyflow.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RegionGeometryTest {
    private val rect = ScreenRegion(100, 100, 900, 900)

    @Test fun posicaoInicialETelaInteira() {
        assertThat(RegionGeometry.initial(1000, 2000)).isEqualTo(ScreenRegion(200, 400, 800, 1600))
        assertThat(RegionGeometry.full(1000, 2000)).isEqualTo(ScreenRegion(0, 0, 1000, 2000))
    }

    @Test fun quatroCantosRespeitamLimitesETamanhoMinimo() {
        for (corner in RegionGeometry.Corner.entries) {
            for ((x, y) in listOf(-100f to -100f, 1100f to 1100f, 500f to 500f)) {
                val moved = RegionGeometry.move(rect, corner, x, y, 1000, 1000, 40)
                assertThat(moved.left).isAtLeast(0); assertThat(moved.top).isAtLeast(0)
                assertThat(moved.right).isAtMost(1000); assertThat(moved.bottom).isAtMost(1000)
                assertThat(moved.width).isAtLeast(40); assertThat(moved.height).isAtLeast(40)
                when (corner) {
                    RegionGeometry.Corner.TOP_LEFT -> { assertThat(moved.right).isEqualTo(900); assertThat(moved.bottom).isEqualTo(900) }
                    RegionGeometry.Corner.TOP_RIGHT -> { assertThat(moved.left).isEqualTo(100); assertThat(moved.bottom).isEqualTo(900) }
                    RegionGeometry.Corner.BOTTOM_LEFT -> { assertThat(moved.right).isEqualTo(900); assertThat(moved.top).isEqualTo(100) }
                    RegionGeometry.Corner.BOTTOM_RIGHT -> { assertThat(moved.left).isEqualTo(100); assertThat(moved.top).isEqualTo(100) }
                }
            }
        }
    }

    @Test fun hitTestSoSelecionaUmHandleProximo() {
        RegionGeometry.corners(rect).forEach { (corner, point) ->
            assertThat(RegionGeometry.hit(rect, point.first + 3, point.second + 3, 32f)).isEqualTo(corner)
        }
        assertThat(RegionGeometry.hit(rect, 500f, 500f, 32f)).isNull()
    }

    @Test fun minimoMaiorQueTelaNaoProduzCoordenadasNegativas() {
        val small = RegionGeometry.full(10, 10)
        assertThat(RegionGeometry.move(small, RegionGeometry.Corner.TOP_LEFT, 9f, 9f, 10, 10, 32)).isEqualTo(small)
        assertThat(RegionGeometry.move(small, RegionGeometry.Corner.BOTTOM_RIGHT, 0f, 0f, 10, 10, 32)).isEqualTo(small)
    }
}

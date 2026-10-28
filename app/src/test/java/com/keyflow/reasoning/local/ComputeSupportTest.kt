package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import org.junit.Test

/** A detecção de onde o aparelho roda um modelo de texto: CPU sempre, GPU com a `libOpenCL.so` do fabricante. */
class ComputeSupportTest {

    @Test
    fun `a device with no OpenCL library offers only the CPU`() {
        val support = ComputeSupport(fileExists = { false })
        assertThat(support.gpuAvailable).isFalse()
        assertThat(support.backends).containsExactly(ComputeBackend.CPU)
    }

    @Test
    fun `an OpenCL library in any of the 64 bit vendor and system folders offers the GPU after the CPU`() {
        ComputeSupport.OPENCL_LIBRARIES.forEach { path ->
            val support = ComputeSupport(fileExists = { it == path })
            assertThat(support.gpuAvailable).isTrue()
            assertThat(support.backends).containsExactly(ComputeBackend.CPU, ComputeBackend.GPU).inOrder()
        }
    }

    @Test
    fun `only the paths of the 64 bit OpenCL loader are probed`() {
        assertThat(ComputeSupport.OPENCL_LIBRARIES).containsExactly(
            "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so",
        )
    }

    @Test
    fun `some other library in the vendor folder is not an OpenCL library`() {
        val support = ComputeSupport(fileExists = { it == "/vendor/lib64/libEGL.so" })
        assertThat(support.gpuAvailable).isFalse()
    }

    @Test
    fun `the probe runs once, whatever the number of reads`() {
        var probes = 0
        val support = ComputeSupport(fileExists = { probes++; false })
        repeat(5) { support.gpuAvailable; support.backends }
        assertThat(probes).isEqualTo(ComputeSupport.OPENCL_LIBRARIES.size)
    }

    @Test
    fun `the options for a given availability keep the CPU first`() {
        assertThat(ComputeSupport.backendsFor(false)).containsExactly(ComputeBackend.CPU)
        assertThat(ComputeSupport.backendsFor(true)).containsExactly(ComputeBackend.CPU, ComputeBackend.GPU).inOrder()
    }

    /** A decisão do Yan (NPU fora): nenhum modelo nem backend de NPU existe no app. */
    @Test
    fun `there is no NPU backend`() {
        assertThat(ComputeBackend.entries.map { it.name }).containsExactly("CPU", "GPU").inOrder()
    }
}

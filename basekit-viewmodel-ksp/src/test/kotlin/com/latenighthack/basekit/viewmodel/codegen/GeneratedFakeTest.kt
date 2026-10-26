package com.latenighthack.basekit.viewmodel.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedFakeTest {
    private val real = ViewModelFactoryInfo("homeFactory", "com.app.RealHomeViewModel", listOf("com.app.HomeNavigator"))
    private val fake = real.copy(implQualifiedName = "com.app.FakeHomeViewModel", fake = true)

    @Test
    fun aGeneratedFakeWinsWithoutAWarning() {
        val warnings = mutableListOf<String>()
        assertEquals(listOf(fake), uniqueFactories(listOf(real, fake), warnings::add))
        // Fake sorts after the real one by name too.
        val zFake = fake.copy(implQualifiedName = "com.app.ZFakeHomeViewModel")
        assertEquals(listOf(zFake), uniqueFactories(listOf(real, zFake), warnings::add))
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun twoRealImplementationsStillWarn() {
        val warnings = mutableListOf<String>()
        val other = real.copy(implQualifiedName = "com.app.OtherHomeViewModel")
        assertEquals(listOf(other), uniqueFactories(listOf(real, other), warnings::add))
        assertEquals(1, warnings.size)
    }

    @Test
    fun aGeneratedFakeReplacesTheBindingOfItsSpec() {
        val realProbe = InjectVmInfo("com.app.Probe", "com.app.RealProbe")
        val fakeProbe = InjectVmInfo("com.app.Probe", "com.app.FakeProbe", fake = true)
        val other = InjectVmInfo("com.app.Other", "com.app.RealOther")
        assertEquals(listOf(fakeProbe, other), uniqueBindings(listOf(realProbe, fakeProbe, other)))
        assertEquals(listOf(realProbe, other), uniqueBindings(listOf(realProbe, other)))
    }
}

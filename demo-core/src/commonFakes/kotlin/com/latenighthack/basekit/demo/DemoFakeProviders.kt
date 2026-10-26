package com.latenighthack.basekit.demo

import com.latenighthack.basekit.fake.FakeData
import com.latenighthack.basekit.fake.annotations.FakeProvider
import com.latenighthack.basekit.fake.annotations.FakeTextKind

// Compiled only when fakes are on (`-Pbasekit.fakes=true`): providers use the fake runtime.

/** Every fake ProbeMessage is a sentence. */
@FakeProvider
fun FakeData.probeMessage(): ProbeMessage = ProbeMessage(text("text", FakeTextKind.SENTENCE))

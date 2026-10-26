package com.latenighthack.basekit.fake

/**
 * The settings fakes read when they are created, and the log of what they were asked to do.
 *
 * Set [config] before creating the first view model (before rooting a navigator, say). A fake reads it
 * once, when it is created, so a change shows on screens opened afterwards.
 */
public object BasekitFakes {
    public var config: FakeConfig = FakeConfig()

    public val log: FakeLog = FakeLog()

    /** The data for the fake whose path is [path], from [config]. */
    public fun data(path: String): FakeData = FakeData(config, path)

    /**
     * Runs [block] with [config] in place of the current one, then restores it. Not thread-safe: fakes
     * created on other threads meanwhile see it too.
     */
    public fun <T> using(config: FakeConfig, block: () -> T): T {
        val previous = this.config
        this.config = config
        try {
            return block()
        } finally {
            this.config = previous
        }
    }
}

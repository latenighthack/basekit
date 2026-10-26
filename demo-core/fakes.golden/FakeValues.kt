package com.latenighthack.basekit.demo

import com.latenighthack.basekit.demo.probeMessage as provider0

/** Builds the fake values of each type the fakes use. */
internal object FakeValues {
    fun provider0Value(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.ProbeMessage = data.provider0()

    fun homeViewModelState(data: com.latenighthack.basekit.fake.FakeData, args: com.latenighthack.basekit.demo.HomeViewModel.Args?): com.latenighthack.basekit.demo.HomeViewModel.State = com.latenighthack.basekit.demo.HomeViewModel.State(
        title = data.text("title"),
        subtitle = data.nullable("subtitle") { data.text("subtitle") },
    )

    fun detailViewModelArgs(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.DetailViewModel.Args {
        val args = com.latenighthack.basekit.demo.DetailViewModel.Args()
        args.id = data.text("id")
        return args
    }

    fun pickerViewModelArgs(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.PickerViewModel.Args {
        val args = com.latenighthack.basekit.demo.PickerViewModel.Args()
        return args
    }

    fun feedItemViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.FeedItemViewModel.State = com.latenighthack.basekit.demo.FeedItemViewModel.State(
        title = data.text("title", com.latenighthack.basekit.fake.annotations.FakeTextKind.TITLE),
        subtitle = data.text("subtitle"),
        id = data.text("id"),
    )

    fun detailViewModelState(data: com.latenighthack.basekit.fake.FakeData, args: com.latenighthack.basekit.demo.DetailViewModel.Args?): com.latenighthack.basekit.demo.DetailViewModel.State = com.latenighthack.basekit.demo.DetailViewModel.State(
        id = args?.id ?: data.text("id"),
        body = data.text("body"),
        count = data.int("count"),
        note = data.text("note"),
        flagged = data.boolean("flagged"),
        tags = data.list("tags", data.size("tags")) { item0 -> item0.text("tags") },
    )

    fun bindingProbeViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.BindingProbeViewModel.State = com.latenighthack.basekit.demo.BindingProbeViewModel.State(
        message = com.latenighthack.basekit.demo.FakeValues.provider0Value(data.child("message")),
        optionalMessage = data.nullable("optionalMessage") { com.latenighthack.basekit.demo.FakeValues.provider0Value(data.child("optionalMessage")) },
        failure = when (data.choose("failure", intArrayOf(1, 1))) { 0 -> com.latenighthack.basekit.demo.ProbeFailure.NONE; else -> com.latenighthack.basekit.demo.ProbeFailure.RETRY },
        note = data.nullable("note") { data.text("note") },
        tags = data.list("tags", data.size("tags")) { item0 -> item0.text("tags") },
    )

    fun bindingProbeViewModelSectionGroup(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.BindingProbeViewModel.Section.Group = com.latenighthack.basekit.demo.BindingProbeViewModel.Section.Group(
        name = data.text("name"),
    )

    fun bindingNoteViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.BindingNoteViewModel.State = com.latenighthack.basekit.demo.BindingNoteViewModel.State(
        text = data.text("text"),
    )

    fun bindingChildViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.BindingChildViewModel.State = com.latenighthack.basekit.demo.BindingChildViewModel.State(
        title = data.text("title"),
    )

    fun pickerViewModelState(data: com.latenighthack.basekit.fake.FakeData, args: com.latenighthack.basekit.demo.PickerViewModel.Args?): com.latenighthack.basekit.demo.PickerViewModel.State = com.latenighthack.basekit.demo.PickerViewModel.State(
        title = data.text("title"),
    )

    fun pickResult(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.PickResult = com.latenighthack.basekit.demo.PickResult(
        selectedId = data.int("selectedId"),
    )

    fun pickerEmptyViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.PickerEmptyViewModel.State = com.latenighthack.basekit.demo.PickerEmptyViewModel.State(
        message = data.text("message"),
    )

    fun pickerOptionViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.PickerOptionViewModel.State = com.latenighthack.basekit.demo.PickerOptionViewModel.State(
        label = data.text("label"),
        id = data.int("id"),
    )

    fun splashViewModelState(data: com.latenighthack.basekit.fake.FakeData, args: com.latenighthack.basekit.demo.SplashViewModel.Args?): com.latenighthack.basekit.demo.SplashViewModel.State = com.latenighthack.basekit.demo.SplashViewModel.State(
        message = data.text("message"),
    )

    fun homeViewModelArgs(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.HomeViewModel.Args {
        val args = com.latenighthack.basekit.demo.HomeViewModel.Args()
        args.welcome = data.boolean("welcome")
        args.referrer = data.nullable("referrer") { data.text("referrer") }
        return args
    }

    fun overviewViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.OverviewViewModel.State = com.latenighthack.basekit.demo.OverviewViewModel.State(
        title = data.text("title"),
    )

    fun overviewSectionViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.OverviewSectionViewModel.State = com.latenighthack.basekit.demo.OverviewSectionViewModel.State(
        heading = data.text("heading"),
    )

    fun credentialItemViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.CredentialItemViewModel.State = com.latenighthack.basekit.demo.CredentialItemViewModel.State(
        name = data.text("name"),
    )

    fun noCredentialsItemViewModelState(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.NoCredentialsItemViewModel.State = com.latenighthack.basekit.demo.NoCredentialsItemViewModel.State(
        message = data.text("message"),
    )

    fun splashViewModelArgs(data: com.latenighthack.basekit.fake.FakeData): com.latenighthack.basekit.demo.SplashViewModel.Args {
        val args = com.latenighthack.basekit.demo.SplashViewModel.Args()
        return args
    }
}

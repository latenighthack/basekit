package com.latenighthack.basekit.viewmodel

import com.latenighthack.deltalist.react.ReactDeltaListStore
import com.latenighthack.deltalist.react.ReactSectionedDeltaListStore
import kotlinx.coroutines.flow.Flow

/**
 * What a generated `use{Vm}` hook reads from and calls on one view model type, in the order the
 * returned object lists them: State properties, actions and mutators, lists, then children.
 * [useReactViewModel] does the rest. Every accessor is a compiled lambda, because production
 * Kotlin/JS renames members.
 */
public class ReactDescriptor<VM : ViewModel<S>, S> @PublishedApi internal constructor(
    internal val name: String,
    internal val simpleName: String,
) {
    internal val fields = ArrayList<ReactField<S>>()
    internal val calls = ArrayList<ReactCall<VM>>()
    internal val lists = ArrayList<ReactList<VM>>()
    internal val children = ArrayList<ReactChild<VM>>()
    internal val bindings = ReactBindingCache<VM, ReactDescribedBinding<VM, S>> { ReactDescribedBinding(this, it) }

    /** A State property passed as [read] returns it. */
    public fun field(name: String, read: (S) -> Any?) {
        fields.add(ReactField(name, read, null))
    }

    /** A State property [convert]ed to a new JS value, reused while [read] returns the same value. */
    public fun <T> converted(name: String, read: (S) -> T, convert: (T) -> dynamic) {
        fields.add(ReactField(name, read, convert.unsafeCast<(Any?) -> dynamic>()))
    }

    /**
     * A zero-argument action, a `Promise`-returning function created once per view model. Pass a
     * function reference (`Spec::action`): unlike a suspend lambda, it compiles to no state machine.
     */
    public fun action(name: String, call: suspend (VM) -> Unit) {
        calls.add(ReactCall(name, call, null, null))
    }

    /**
     * A single-argument mutator, called with the JS value [convert]ed, or as it is without one. A
     * conversion that throws rejects the call's `Promise`.
     */
    public fun <T> mutator(name: String, call: suspend (VM, T) -> Unit, convert: ((dynamic) -> T)? = null) {
        calls.add(ReactCall(name, null, call.unsafeCast<suspend (VM, dynamic) -> Unit>(), convert.unsafeCast<((dynamic) -> dynamic)?>()))
    }

    /** A `@ViewModelList`, read as a delegated array whose elements are [rows]. */
    public fun list(name: String, source: (VM) -> Flow<*>, rows: ReactRows) {
        lists.add(ReactList(name, source, null, rows))
    }

    /** A sectioned `@ViewModelList`; [header] gives each section header's React value. */
    public fun sectionedList(name: String, source: (VM) -> Flow<*>, header: (Any?) -> Any, rows: ReactRows) {
        lists.add(ReactList(name, source, header, rows))
    }

    /** A `@ChildViewModel`, read with its own generated hook. */
    public fun child(name: String, use: (VM) -> dynamic) {
        children.add(ReactChild(name, use))
    }
}

/** A descriptor for the view model named [name] ([simpleName] in error messages). */
public inline fun <VM : ViewModel<S>, S> reactDescriptor(
    name: String,
    simpleName: String,
    build: ReactDescriptor<VM, S>.() -> Unit,
): ReactDescriptor<VM, S> = ReactDescriptor<VM, S>(name, simpleName).apply(build)

internal class ReactField<S>(val name: String, val read: (S) -> Any?, val convert: ((Any?) -> dynamic)?)
internal class ReactCall<VM>(
    val name: String,
    val action: (suspend (VM) -> Unit)?,
    val mutator: (suspend (VM, dynamic) -> Unit)?,
    val convert: ((dynamic) -> dynamic)?,
)
internal class ReactList<VM>(val name: String, val source: (VM) -> Flow<*>, val header: ((Any?) -> Any)?, val rows: ReactRows)
internal class ReactChild<VM>(val name: String, val use: (VM) -> dynamic)

/**
 * The closed set of row types a list emits. Lists of the same row types share one. Each row becomes
 * `{ kind, id?, key?, use }`, and `use()` calls the row's generated hook.
 */
public class ReactRows(vararg rows: ReactRow) {
    private val rows: Array<out ReactRow> = rows

    internal fun element(raw: Any?, observed: ReactObservedRows, viewModel: String, list: String): Any {
        for (row in rows) {
            if (!row.matches(raw)) continue
            val child = observed.get(raw!!, row.observe)
            val result: dynamic = js("({})")
            result.kind = row.kind
            val id = row.id
            if (id != null) {
                result.id = id(child)
                result.key = result.id
            }
            val key = row.key
            if (key != null) result.key = row.keyPrefix + key(child)
            result.use = { row.use(child) }
            return result
        }
        error("$viewModel.$list emitted an undeclared child type: ${raw?.let { it::class.simpleName }}")
    }
}

/** One row type of a [ReactRows]; see [reactRow]. */
public class ReactRow @PublishedApi internal constructor(
    internal val kind: String,
    internal val matches: (Any?) -> Boolean,
    internal val observe: (Any, ViewModelObserver) -> Any,
    internal val use: (Any) -> dynamic,
    internal val id: ((Any) -> Any?)?,
    internal val keyPrefix: String,
    internal val key: ((Any) -> Any?)?,
)

/**
 * Rows of type [T] are `{ kind }`. [id] sets both `id` and `key`, from the row's id. [key] sets
 * `key` to [keyPrefix] followed by the row's identity property.
 */
public inline fun <reified T : Any> reactRow(
    kind: String,
    noinline observe: (T, ViewModelObserver) -> T,
    noinline use: (T) -> dynamic,
    noinline id: ((T) -> Any?)? = null,
    keyPrefix: String = "",
    noinline key: ((T) -> Any?)? = null,
): ReactRow = ReactRow(
    kind,
    { it is T },
    // Only rows that passed the check reach these, so the casts are not checked again.
    observe.unsafeCast<(Any, ViewModelObserver) -> Any>(),
    use.unsafeCast<(Any) -> dynamic>(),
    id.unsafeCast<((Any) -> Any?)?>(),
    keyPrefix,
    key.unsafeCast<((Any) -> Any?)?>(),
)

/** A sealed section header's React value, `{ kind }`. */
public fun reactHeaderKind(kind: String): Any {
    val header: dynamic = js("({})")
    header.kind = kind
    return header
}

/**
 * The React binding of [viewModel] described by [descriptor]. The stores, action functions and
 * returned object are shared by every component bound to the same instance, and the object keeps
 * its identity while the state, every list revision and every child's object are unchanged.
 */
public fun <VM : ViewModel<S>, S> useReactViewModel(descriptor: ReactDescriptor<VM, S>, viewModel: VM): dynamic {
    val binding = descriptor.bindings.get(viewModel)
    val lists = binding.lists
    val inputs = arrayOfNulls<Any?>(1 + lists.size + descriptor.children.size).unsafeCast<Array<dynamic>>()
    // One external-store read per list and one hook per child: a fixed count, so hook order is stable.
    inputs[0] = React.useSyncExternalStore(binding.core.state.subscribe, binding.core.state.getSnapshot)
    for (index in lists.indices) {
        inputs[1 + index] = React.useSyncExternalStore(lists[index].subscribe, lists[index].getSnapshot)
    }
    descriptor.children.forEachIndexed { index, child -> inputs[1 + lists.size + index] = child.use(viewModel) }
    return binding.core.result(inputs) { binding.build(inputs) }
}

internal class ReactDescribedBinding<VM : ViewModel<S>, S>(
    private val descriptor: ReactDescriptor<VM, S>,
    viewModel: VM,
) {
    val core: ReactViewModelBinding<S> = ReactViewModelBinding(viewModel, descriptor.name)
    private val conversions = descriptor.fields.map { field -> field.convert?.let { ReactConversion(it) } }
    private val calls: List<dynamic> = descriptor.calls.map { call ->
        val action = call.action
        val mutator = call.mutator
        val convert = call.convert
        if (action != null) {
            val function: () -> dynamic = { core.run { action(viewModel) } }
            function
        } else {
            val function: (dynamic) -> dynamic = { value ->
                core.run { mutator!!(viewModel, if (convert != null) convert(value) else value) }
            }
            function
        }
    }
    val lists: List<ReactBoundList> = if (descriptor.lists.isEmpty()) emptyList() else {
        // Rows report to this view model's observer, one decorator per row.
        val observed = ReactObservedRows(viewModel.viewModelObserver())
        descriptor.lists.map { list ->
            val source = viewModel.boundList(descriptor.name, list.name, list.source(viewModel))
            val rows: (Any?) -> Any? = { raw -> list.rows.element(raw, observed, descriptor.simpleName, list.name) }
            val header = list.header
            if (header == null) {
                val store = ReactDeltaListStore(source, rows)
                ReactBoundList(store.subscribe, store.getSnapshot, store.proxy)
            } else {
                val store = ReactSectionedDeltaListStore(source, header, rows)
                ReactBoundList(store.subscribe, store.getSnapshot, store.handle)
            }
        }
    }

    fun build(inputs: Array<dynamic>): dynamic {
        val state = inputs[0].unsafeCast<S>()
        val result: dynamic = js("({})")
        descriptor.fields.forEachIndexed { index, field ->
            val value = field.read(state)
            val conversion = conversions[index]
            result[field.name] = if (conversion != null) conversion(value) else value
        }
        descriptor.calls.forEachIndexed { index, call -> result[call.name] = calls[index] }
        descriptor.lists.forEachIndexed { index, list -> result[list.name] = lists[index].value }
        descriptor.children.forEachIndexed { index, child -> result[child.name] = inputs[1 + lists.size + index] }
        return result
    }
}

internal class ReactBoundList(
    val subscribe: (() -> Unit) -> () -> Unit,
    val getSnapshot: () -> Int,
    val value: Any,
)

/**
 * The entry of [entries] whose React spelling in [names] (at the same index) is [value]. Throws
 * `IllegalArgumentException` naming [type] and the expected spellings otherwise.
 */
public fun <E : Enum<E>> reactEnumValue(value: dynamic, names: Array<String>, entries: Array<E>, type: String): E {
    val name = value.unsafeCast<String>()
    val index = names.indexOf(name)
    if (index < 0) throw IllegalArgumentException("Not a $type: " + name + "; expected one of " + names.joinToString(", "))
    return entries[index]
}

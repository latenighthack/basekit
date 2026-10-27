package com.latenighthack.basekit.navigation.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AppleSwiftNavigationGeneratorTest {
    @Test
    fun isolatesTheKotlinNavigationHostConformanceToTheMainActor() {
        val output = RecordingSwiftCodeGenerator()
        val destinations = listOf(
            destination(
                simpleName = "HomeViewModel",
                qualifiedName = "sample.HomeViewModel",
                navName = "home",
                edges = listOf(NavEdge("home", "onOpenRoom", "sample.RoomViewModel")),
            ),
            destination(
                simpleName = "RoomViewModel",
                qualifiedName = "sample.RoomViewModel",
                navName = "room",
            ),
        )

        AppleSwiftNavigationGenerator(output, Dependencies(false), listOf("SampleKit"))
            .generate(destinations)

        val source = output.source()
        assertContains(
            source,
            "BasekitNavigationRouter: NSObject, ObservableObject, @MainActor BasekitNavigationHost",
        )
        assertFalse("@preconcurrency BasekitNavigationHost" in source)
        assertContains(
            source,
            "private static func replacingResponder(in route: BasekitRoute, with responder: NavigationResponder)",
        )
    }

    @Test
    fun keepsBothEdgesOfAFanOutActionDisambiguatedByTarget() {
        val output = RecordingSwiftCodeGenerator()
        val destinations = listOf(
            destination(
                simpleName = "ContactsViewModel",
                qualifiedName = "sample.ContactsViewModel",
                navName = "contacts",
                edges = listOf(
                    NavEdge("contacts", "onSearchChanged", "sample.ScanViewModel"),
                    NavEdge("contacts", "onSearchChanged", "sample.RoomViewModel"),
                ),
            ),
            destination("ScanViewModel", "sample.ScanViewModel", "scan"),
            destination("RoomViewModel", "sample.RoomViewModel", "room"),
        )

        AppleSwiftNavigationGenerator(output, Dependencies(false), listOf("SampleKit"))
            .generate(destinations)

        val source = output.source()
        // both fan-out targets keep a distinct BasekitNavigationEdge case...
        assertContains(source, "case contactsOnSearchChangedToScan")
        assertContains(source, "case contactsOnSearchChangedToRoom")
        // ...and the NavigationEdge(_:) bridge maps both (matching the Kotlin enum's disambiguation)
        assertContains(source, "self = .contactsOnSearchChangedToScan")
        assertContains(source, "self = .contactsOnSearchChangedToRoom")
    }

    @Test
    fun opensADeepLinkedRespondingDestinationWithADiscardingResponder() {
        val output = RecordingSwiftCodeGenerator()
        val destinations = listOf(
            destination(
                simpleName = "HomeViewModel",
                qualifiedName = "sample.HomeViewModel",
                navName = "home",
                edges = listOf(NavEdge("home", "onSetUp", "sample.SetupViewModel")),
            ),
            destination("SetupViewModel", "sample.SetupViewModel", "setup").copy(
                argsQualifiedName = "sample.SetupViewModel.Args",
                argsSwiftName = "SetupViewModelArgs",
                responseQualifiedName = "sample.SetupResult",
                responseSwiftName = "SetupResult",
            ),
        )

        AppleSwiftNavigationGenerator(output, Dependencies(false), listOf("SampleKit"))
            .generate(destinations)

        val source = output.source()
        assertContains(source, "private final class BasekitDiscardingResponder: NSObject, NavigationResponder")
        assertContains(source, "case .setup(_, _, .none): return true")
        assertContains(source, "let responder: NavigationResponder? = Self.lacksResponder(route) ? BasekitDiscardingResponder() : nil")
        // Both the edge-less deep link and the presented route go through the closing proxy.
        assertContains(source, "path = [.init(id: entryID, route: attach(responder, to: route, entryID: entryID))]")
        assertContains(source, "receive(ownerID: ownerID, route: route, edge: edge, context: nil, responder: responder)")
        assertContains(source, "let routed = attach(responder, to: route, entryID: entryID)")
    }

    @Test
    fun givesEachPresentedEntryARouterThatItsSheetsStackOn() {
        val output = RecordingSwiftCodeGenerator()
        val destinations = listOf(
            destination(
                simpleName = "HomeViewModel",
                qualifiedName = "sample.HomeViewModel",
                navName = "home",
                edges = listOf(NavEdge("home", "onOpenRoom", "sample.RoomViewModel")),
            ),
            destination("RoomViewModel", "sample.RoomViewModel", "room"),
        )

        AppleSwiftNavigationGenerator(output, Dependencies(false), listOf("SampleKit"))
            .generate(destinations)

        val source = output.source()
        // A sheet, cover or custom entry gets a router of its own, with the presenter's policy.
        assertContains(source, "children[entry.id] = BasekitNavigationRouter(parent: self, policy: policy)")
        // It uses its parent's platform presenter as that is when it presents, and once its entry has
        // gone it answers whatever its view model still opens with nil.
        assertContains(source, "private var presenter: BasekitPlatformPresenter? { platformPresenter ?? parentRouter?.presenter }")
        assertContains(source, "if closed { responder?.respond(response: nil); return }")
        assertContains(source, "if let replacement { adopt(replacement) }; slot = replacement")
        assertContains(source, "if let presenter { adopt(entry); customEntries[entry.id] = entry")
        assertContains(source, "public func host(for entry: BasekitNavigationEntry) -> BasekitNavigationRouter { router(holding: entry) ?? self }")
        // An entry that leaves takes everything presented over it, and their responders answer nil.
        assertContains(source, "if let removedID { responders.removeValue(forKey: removedID)?.respond(response: nil); dropChild(removedID) }")
        assertContains(source, "private func dropChild(_ entryID: UUID) { children.removeValue(forKey: entryID)?.tearDown() }")
        // The stack host gives each entry its host, and shows a presented entry's own stack.
        assertContains(source, "root(rootEntry, router, namespace)")
        assertContains(source, "transitioned(entry, destination(entry, router, namespace))")
        assertContains(source, "AnyView(BasekitPresentedStackHost(parent: router, entry: entry, destination: destination))")
        assertContains(source, "let host = parent.host(for: entry); _router = StateObject(wrappedValue: host)")
    }

    private fun destination(
        simpleName: String,
        qualifiedName: String,
        navName: String,
        edges: List<NavEdge> = emptyList(),
    ) = DestinationInfo(
        simpleName = simpleName,
        qualifiedName = qualifiedName,
        navName = navName,
        argsQualifiedName = null,
        argsSwiftName = null,
        responseQualifiedName = null,
        responseSwiftName = null,
        routePath = null,
        routeArgs = emptyList(),
        edges = edges,
    )
}

private class RecordingSwiftCodeGenerator : CodeGenerator {
    private val output = ByteArrayOutputStream()

    fun source(): String = output.toString(Charsets.UTF_8.name())

    override fun createNewFile(
        dependencies: Dependencies,
        packageName: String,
        fileName: String,
        extensionName: String,
    ): OutputStream = output

    override fun createNewFileByPath(
        dependencies: Dependencies,
        path: String,
        extensionName: String,
    ): OutputStream = output

    override fun associate(
        sources: List<KSFile>,
        packageName: String,
        fileName: String,
        extensionName: String,
    ) = Unit

    override fun associateByPath(sources: List<KSFile>, path: String, extensionName: String) = Unit

    override fun associateWithClasses(
        classes: List<KSClassDeclaration>,
        packageName: String,
        fileName: String,
        extensionName: String,
    ) = Unit

    override val generatedFile: Collection<File> = emptyList()
}

package com.floxy.viewer.render

import com.floxy.viewer.psi.VisitorBasedFlowAnalyzer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MermaidRendererTest {

    @Test
    fun `mermaid renderer applies colors by step type`() {
        val analyzer = VisitorBasedFlowAnalyzer()
        val testCode = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("color-test", 1).
                Step("start", "init-service").
                Fork("parallel-work",
                    func(branch *floxy.Builder) { branch.Step("task-a", "service-a") },
                    func(branch *floxy.Builder) { branch.Step("task-b", "service-b") },
                ).
                JoinStep("merge", []string{"task-a", "task-b"}, floxy.JoinStrategyAll).
                Build()
            }
        """.trimIndent()

        val flows = analyzer.collectFlowsFromText(testCode)
        assertEquals(1, flows.size)
        val model = flows[0]

        val renderer = MermaidRenderer()
        val mermaid = renderer.render(model)

        println("Generated Mermaid diagram:")
        println(mermaid)

        // Node ids in Mermaid are alias'ed (non-alnum -> _)
        assertTrue(mermaid.contains("style start fill:#E3F2FD"), "Task nodes should have light blue fill")
        assertTrue(mermaid.contains("style parallel_work fill:#FFF3E0"), "Fork nodes should have light orange fill")
        assertTrue(mermaid.contains("style merge fill:#E8F5E9"), "Join nodes should have light green fill")
    }
}

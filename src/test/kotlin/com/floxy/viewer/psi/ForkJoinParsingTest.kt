package com.floxy.viewer.psi

import com.floxy.viewer.model.FlowStepType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ForkJoinParsingTest {

    @Test
    fun `parser supports Join sugar after Fork`() {
        val code = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("join-sugar", 1).
                Step("start", "init").
                Fork("fork",
                    func(branch *floxy.Builder) { branch.Step("a1", "ha1") },
                    func(branch *floxy.Builder) { branch.Step("b1", "hb1") },
                ).
                Join("join", floxy.JoinStrategyAll).
                Step("end", "finish").
                Build()
            }
        """.trimIndent()

        val analyzer = VisitorBasedFlowAnalyzer()
        val results = analyzer.analyzeFlowsDetailed(code)
        assertEquals(1, results.size)
        val model = results[0].model

        assertTrue(model.steps.containsKey("fork"), "Fork step should be present")
        val join = model.steps["join"]
        assertNotNull(join, "Join step should be present")
        assertEquals(FlowStepType.Join, join!!.type)
        assertEquals("floxy.JoinStrategyAll", join.joinStrategy)
        // Ensure there are split edges from fork and join edges to join
        assertTrue(model.edges.any { it.from == "fork" && it.kind == "split" }, "Expected split edges from fork")
        assertTrue(model.edges.any { it.to == "join" && it.kind == "join" }, "Expected join edges into join node")
    }

    @Test
    fun `parser supports ForkJoin sugar`() {
        val code = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("forkjoin-sugar", 1).
                Step("start", "init").
                ForkJoin("fork",
                    []func(b *floxy.Builder){
                        func(b *floxy.Builder) { b.Step("a1", "ha1") },
                        func(b *floxy.Builder) { b.Step("b1", "hb1") },
                    },
                    "join", floxy.JoinStrategyAll,
                ).
                Step("end", "finish").
                Build()
            }
        """.trimIndent()

        val analyzer = VisitorBasedFlowAnalyzer()
        val results = analyzer.analyzeFlowsDetailed(code)
        assertEquals(1, results.size)
        val model = results[0].model

        assertTrue(model.steps.containsKey("fork"), "Fork step should be present")
        val join = model.steps["join"]
        assertNotNull(join, "Join step should be present")
        assertEquals(FlowStepType.Join, join!!.type)
        assertEquals("floxy.JoinStrategyAll", join.joinStrategy)
        // Ensure model has edges for split and join
        assertTrue(model.edges.any { it.from == "fork" && it.kind == "split" }, "Expected split edges from fork")
        assertTrue(model.edges.any { it.to == "join" && it.kind == "join" }, "Expected join edges into join node")
    }
}

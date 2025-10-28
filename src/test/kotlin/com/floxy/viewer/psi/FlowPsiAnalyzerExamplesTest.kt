package com.floxy.viewer.psi

import com.floxy.viewer.model.FlowStepType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FlowPsiAnalyzerExamplesTest {

    @Test
    fun `hello_world example is parsed`() {
        val text = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("hello-world", 1).
                Step("say-hello", "hello", floxy.WithStepMaxRetries(3)).
                Build()
            }
        """.trimIndent()
        val analyzer = VisitorBasedFlowAnalyzer()
        val flows = analyzer.collectFlowsFromText(text)
        
        assertTrue(flows.isNotEmpty(), "No flows detected in hello_world example")
        val flow = flows.firstOrNull { it.name == "hello-world" } ?: fail("Flow 'hello-world' not found")
        assertEquals(1, flow.version)

        val step = flow.steps["say-hello"] ?: fail("Step 'say-hello' not found")
        assertEquals(FlowStepType.Task, step.type)
        assertEquals("hello", step.handler)

        // For a single-step chain, there might be no incoming edges to the first step — that's OK.
        // We just ensure at least the step is present as parsed above.
    }

    @Test
    fun `microservices example core nodes are parsed`() {
        val text = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("microservices-orchestration", 1).
                Step("validate-user", "user-service", floxy.WithStepMaxRetries(3)).
                OnFailure("compensate-user-validation", "compensation").
                Fork("process-payment-and-inventory",
                    func(branch *floxy.Builder) { branch.Step("process-payment", "payment-service") },
                    func(branch *floxy.Builder) { branch.Step("check-inventory", "inventory-service") },
                ).
                JoinStep("send-notifications", []string{"process-payment", "check-inventory"}, floxy.JoinStrategyAll).
                Fork("track-analytics",
                    func(branch *floxy.Builder) { branch.Step("track-event", "analytics-service") },
                    func(branch *floxy.Builder) { branch.Step("audit-action", "audit-service") },
                ).
                JoinStep("finalize-order", []string{"track-event", "audit-action"}, floxy.JoinStrategyAll).
                Build()
            }
        """.trimIndent()
        val analyzer = VisitorBasedFlowAnalyzer()
        val flows = analyzer.collectFlowsFromText(text)

        val flow = flows.firstOrNull { it.name == "microservices-orchestration" }
            ?: fail("Flow 'microservices-orchestration' not found")

        // Validate presence/types of several key nodes in the chain order
        val validateUser = flow.steps["validate-user"] ?: fail("'validate-user' step missing")
        assertEquals(FlowStepType.Task, validateUser.type)
        assertEquals("user-service", validateUser.handler)

        val fork = flow.steps["process-payment-and-inventory"] ?: fail("Fork step missing")
        assertEquals(FlowStepType.Fork, fork.type)

        val join1 = flow.steps["send-notifications"] ?: fail("Join 'send-notifications' missing")
        assertEquals(FlowStepType.Join, join1.type)

        val join2 = flow.steps["finalize-order"] ?: fail("Join 'finalize-order' missing")
        assertEquals(FlowStepType.Join, join2.type)

        // Basic edge sanity: there should be edges between sequentially discovered steps
        assertTrue(flow.edges.isNotEmpty(), "Expected some edges in the microservices flow")
    }

    @Test
    fun `parallel branches are parsed and connected correctly`() {
        val text = """
            package main
            import "github.com/rom8726/floxy"
            func main() {
              _ = floxy.NewBuilder("parallel-test", 1).
                Step("start", "init-service").
                Fork("parallel-work",
                    func(branch *floxy.Builder) { 
                        branch.Step("task-a", "service-a") 
                    },
                    func(branch *floxy.Builder) { 
                        branch.Step("task-b", "service-b") 
                    },
                ).
                JoinStep("merge", []string{"task-a", "task-b"}, floxy.JoinStrategyAll).
                Step("end", "final-service").
                Build()
            }
        """.trimIndent()
        val analyzer = VisitorBasedFlowAnalyzer()
        val flows = analyzer.collectFlowsFromText(text)

        val flow = flows.firstOrNull { it.name == "parallel-test" }
            ?: fail("Flow 'parallel-test' not found")

        // Check main steps
        assertNotNull(flow.steps["start"], "Start step missing")
        assertNotNull(flow.steps["parallel-work"], "Fork step missing")
        assertNotNull(flow.steps["merge"], "Join step missing")
        assertNotNull(flow.steps["end"], "End step missing")

        // Check parallel branch steps
        assertNotNull(flow.steps["task-a_branch_1"], "Parallel branch step 'task-a_branch_1' missing")
        assertNotNull(flow.steps["task-b_branch_2"], "Parallel branch step 'task-b_branch_2' missing")

        // Check fork step has parallel branches info
        val forkStep = flow.steps["parallel-work"] ?: fail("Fork step missing")
        assertEquals(FlowStepType.Fork, forkStep.type)
        assertEquals(2, forkStep.parallelBranches.size, "Fork should have 2 parallel branches")
        assertEquals(listOf("task-a"), forkStep.parallelBranches[0], "First branch should contain task-a")
        assertEquals(listOf("task-b"), forkStep.parallelBranches[1], "Second branch should contain task-b")

        // Check edges: fork should connect to parallel branches
        val forkToBranchA = flow.edges.find { it.from == "parallel-work" && it.to == "task-a_branch_1" && it.kind == "split" }
        assertNotNull(forkToBranchA, "Missing edge from fork to task-a branch")

        val forkToBranchB = flow.edges.find { it.from == "parallel-work" && it.to == "task-b_branch_2" && it.kind == "split" }
        assertNotNull(forkToBranchB, "Missing edge from fork to task-b branch")

        // Check edges: parallel branches should connect to join
        val branchAToJoin = flow.edges.find { it.from == "task-a_branch_1" && it.to == "merge" && it.kind == "join" }
        assertNotNull(branchAToJoin, "Missing edge from task-a branch to join")

        val branchBToJoin = flow.edges.find { it.from == "task-b_branch_2" && it.to == "merge" && it.kind == "join" }
        assertNotNull(branchBToJoin, "Missing edge from task-b branch to join")

        // Check sequential flow
        val startToFork = flow.edges.find { it.from == "start" && it.to == "parallel-work" && it.kind == "next" }
        assertNotNull(startToFork, "Missing edge from start to fork")

        val joinToEnd = flow.edges.find { it.from == "merge" && it.to == "end" && it.kind == "next" }
        assertNotNull(joinToEnd, "Missing edge from join to end")
    }
}

package com.floxy.viewer.psi

import com.floxy.viewer.render.PlantUmlRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NestedForkTest {
    
    @Test
    fun `test nested fork parsing`() {
        val analyzer = VisitorBasedFlowAnalyzer()
        val testCode = """
            workflowDef, err := floxy.NewBuilder("nested-fork-demo", 1).
                Step("start", "init-service").
                Fork("outer-fork", 
                    func(branch1 *floxy.Builder) {
                        branch1.Step("branch1-step1", "service1").
                            Fork("inner-fork1", 
                                func(subBranch1 *floxy.Builder) {
                                    subBranch1.Step("sub1-step1", "sub-service1")
                                },
                                func(subBranch2 *floxy.Builder) {
                                    subBranch2.Step("sub1-step2", "sub-service2")
                                }
                            ).
                            JoinStep("inner-join1", []string{"sub1-step1", "sub1-step2"}, floxy.JoinStrategyAll).
                            Step("branch1-step2", "service1")
                    },
                    func(branch2 *floxy.Builder) {
                        branch2.Step("branch2-step1", "service2").
                            Fork("inner-fork2", 
                                func(subBranch3 *floxy.Builder) {
                                    subBranch3.Step("sub2-step1", "sub-service3")
                                },
                                func(subBranch4 *floxy.Builder) {
                                    subBranch4.Step("sub2-step2", "sub-service4")
                                }
                            ).
                            JoinStep("inner-join2", []string{"sub2-step1", "sub2-step2"}, floxy.JoinStrategyAll).
                            Step("branch2-step2", "service2")
                    }
                ).
                JoinStep("outer-join", []string{"branch1-step1", "branch2-step1"}, floxy.JoinStrategyAll).
                Step("end", "final-service").
                Build()
        """.trimIndent()
        
        val flows = analyzer.collectFlowsFromText(testCode)
        assertEquals(1, flows.size)
        
        val flow = flows[0]
        val renderer = PlantUmlRenderer()
        val plantUml = renderer.render(flow)
        
        println("Nested Fork PlantUML:")
        println(plantUml)
        
        // Check that we have outer fork steps
        assertTrue(plantUml.contains("outer-fork"), "Should contain outer-fork")
        assertTrue(plantUml.contains("branch1-step1"), "Should contain branch1-step1")
        assertTrue(plantUml.contains("branch2-step1"), "Should contain branch2-step1")
        
        // Check that we have inner fork steps (this will likely fail with current implementation)
        assertTrue(plantUml.contains("inner-fork1"), "Should contain inner-fork1")
        assertTrue(plantUml.contains("inner-fork2"), "Should contain inner-fork2")
        assertTrue(plantUml.contains("sub1-step1"), "Should contain sub1-step1")
        assertTrue(plantUml.contains("sub1-step2"), "Should contain sub1-step2")
        assertTrue(plantUml.contains("sub2-step1"), "Should contain sub2-step1")
        assertTrue(plantUml.contains("sub2-step2"), "Should contain sub2-step2")
        
        // Check that we have join steps
        assertTrue(plantUml.contains("inner-join1"), "Should contain inner-join1")
        assertTrue(plantUml.contains("inner-join2"), "Should contain inner-join2")
        assertTrue(plantUml.contains("outer-join"), "Should contain outer-join")
    }
}

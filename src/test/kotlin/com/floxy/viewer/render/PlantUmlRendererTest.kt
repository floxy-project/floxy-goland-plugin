package com.floxy.viewer.render

import com.floxy.viewer.psi.VisitorBasedFlowAnalyzer
import com.floxy.viewer.render.PlantUmlRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class PlantUmlRendererTest {
    
    @Test
    fun `test parallel branches rendering`() {
        val analyzer = VisitorBasedFlowAnalyzer()
        val testCode = """
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
        
        val flows = analyzer.collectFlowsFromText(testCode)
        assertEquals(1, flows.size)
        
        val flow = flows[0]
        val renderer = PlantUmlRenderer()
        val plantUml = renderer.render(flow)
        
        println("Generated PlantUML:")
        println(plantUml)
        
        // Check that PlantUML contains parallel branch steps
        assertTrue(plantUml.contains("task-a"), "PlantUML should contain task-a")
        assertTrue(plantUml.contains("task-b"), "PlantUML should contain task-b")
        assertTrue(plantUml.contains("parallel-work"), "PlantUML should contain fork step")
        assertTrue(plantUml.contains("merge"), "PlantUML should contain join step")
        
        // Check that it contains split and join connections
        assertTrue(plantUml.contains("split"), "PlantUML should contain split connections")
        assertTrue(Pattern.compile("<<fork>>\\s+#FFF3E0").matcher(plantUml).find(), "Fork nodes should have color #FFF3E0")
        assertTrue(Pattern.compile("<<join>>\\s+#E8F5E9").matcher(plantUml).find(), "Join nodes should have color #E8F5E9")
        assertTrue(plantUml.contains("join"), "PlantUML should contain join connections")
    }
}

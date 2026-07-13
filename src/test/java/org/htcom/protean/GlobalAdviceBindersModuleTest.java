/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.htcom.protean.compiler.ModuleClassLoader;
import org.htcom.protean.compiler.RuntimeCompiler;
import org.htcom.protean.module.ModuleContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Global @ControllerAdvice — <b>verifies dynamic registration of @ModelAttribute / @InitBinder</b>.
 *
 * <p>The existing {@link ControllerAdviceModuleTest} only covered @ExceptionHandler advice. Here we confirm
 * that @ModelAttribute (pre-populating the model) and @InitBinder (binding customization) declared by a
 * child-context advice are registered directly into RequestMappingHandlerAdapter's modelAttributeAdviceCache /
 * initBinderAdviceCache on deploy and applied globally, then evicted on undeploy so the ClassLoader is reclaimed.
 *
 * <p>The advice class has no @ExceptionHandler, so the reclaim test pinpoints the adapter cache eviction.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalAdviceBindersModuleTest {

    @Autowired MockMvc mockMvc;
    @Autowired RuntimeCompiler compiler;
    @Autowired ModuleContainer container;

    static final String ADVICE = "runtime.binders.BindersAdvice";
    static final String CONTROLLER = "runtime.binders.BindersController";

    static final Map<String, String> SOURCES = Map.of(
            ADVICE, """
                    package runtime.binders;
                    import org.springframework.web.bind.WebDataBinder;
                    import org.springframework.web.bind.annotation.ControllerAdvice;
                    import org.springframework.web.bind.annotation.InitBinder;
                    import org.springframework.web.bind.annotation.ModelAttribute;
                    @ControllerAdvice
                    public class BindersAdvice {
                        // Pre-populate "injected" into every handler's model.
                        @ModelAttribute("injected")
                        public String injected() { return "from-advice"; }

                        // Binding customization: only convert the sentinel "hi" to avoid interfering with the @ModelAttribute String.
                        @InitBinder
                        public void bind(WebDataBinder binder) {
                            binder.registerCustomEditor(String.class, new java.beans.PropertyEditorSupport() {
                                @Override public void setAsText(String text) {
                                    setValue("hi".equals(text) ? "bound:hi" : text);
                                }
                            });
                        }
                    }
                    """,
            CONTROLLER, """
                    package runtime.binders;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.ModelAttribute;
                    import org.springframework.web.bind.annotation.RequestParam;
                    import org.springframework.web.bind.annotation.RestController;
                    @RestController
                    public class BindersController {
                        @GetMapping("/adv/model")
                        public String model(@ModelAttribute("injected") String injected) { return "model:" + injected; }

                        @GetMapping("/adv/bind")
                        public String bind(@RequestParam("q") String q) { return "bind:" + q; }
                    }
                    """
    );

    @Test
    void model_attribute_and_init_binder_from_module_advice_apply_then_removed() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("binders-mod", loader, List.of(ADVICE, CONTROLLER), CONTROLLER);

        // The handler reads the value that @ModelAttribute pre-populated into the model.
        mockMvc.perform(get("/adv/model"))
                .andExpect(status().isOk())
                .andExpect(content().string("model:from-advice"));

        // The custom editor registered by @InitBinder converts the request-parameter binding.
        mockMvc.perform(get("/adv/bind").param("q", "hi"))
                .andExpect(status().isOk())
                .andExpect(content().string("bind:bound:hi"));

        container.undeploy("binders-mod");
        // Both the advice and the mappings are gone, so 404.
        mockMvc.perform(get("/adv/model")).andExpect(status().isNotFound());
        mockMvc.perform(get("/adv/bind").param("q", "hi")).andExpect(status().isNotFound());
    }

    @Test
    void binders_advice_classloader_reclaimable_after_undeploy() throws Exception {
        ModuleClassLoader loader = compiler.compileAll(SOURCES);
        container.deploy("binders-mod-2", loader, List.of(ADVICE, CONTROLLER), CONTROLLER);
        mockMvc.perform(get("/adv/model")).andExpect(status().isOk());
        container.undeploy("binders-mod-2");

        WeakReference<ClassLoader> ref = new WeakReference<>(loader);
        loader = null;

        boolean reclaimed = false;
        for (int i = 0; i < 8 && !reclaimed; i++) {
            applyMemoryPressure();
            System.gc();
            Thread.sleep(30);
            reclaimed = ref.get() == null;
        }
        assertTrue(reclaimed,
                "an @ModelAttribute/@InitBinder advice module must also have its ClassLoader reclaimed after undeploy "
                        + "(modelAttributeAdviceCache/initBinderAdviceCache must be evicted)");
    }

    private static void applyMemoryPressure() {
        try {
            java.util.List<byte[]> ballast = new java.util.ArrayList<>();
            while (true) {
                ballast.add(new byte[8 * 1024 * 1024]);
            }
        } catch (OutOfMemoryError ignored) {
        }
    }
}

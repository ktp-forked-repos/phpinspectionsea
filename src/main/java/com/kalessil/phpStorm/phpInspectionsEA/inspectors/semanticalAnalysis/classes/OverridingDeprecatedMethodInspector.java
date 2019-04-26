package com.kalessil.phpStorm.phpInspectionsEA.inspectors.semanticalAnalysis.classes;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jetbrains.php.lang.psi.elements.*;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.FeaturedPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.NamedElementUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiResolveUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiTypesUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class OverridingDeprecatedMethodInspector extends BasePhpInspection {
    private static final String patternNeedsDeprecation      = "'%s' overrides/implements a deprecated method. Consider refactoring or deprecate it as well.";
    private static final String patternDeprecateParent       = "The parents' overridden/implemented '%s' probably needs to be deprecated as well.";
    private static final String patternMissingDeprecationTag = "'%s' triggers a deprecation warning, but misses @deprecated annotation.";

    @NotNull
    public String getShortName() {
        return "OverridingDeprecatedMethodInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new FeaturedPhpElementVisitor() {
            @Override
            public void visitPhpFunctionCall(@NotNull FunctionReference reference) {
                if (this.shouldSkipAnalysis(reference, StrictnessCategory.STRICTNESS_CATEGORY_ARCHITECTURE)) { return; }

                final String functionName = reference.getName();
                if (functionName != null && functionName.equals("trigger_error")) {
                    final PsiElement[] arguments = reference.getParameters();
                    if (arguments.length == 2 && arguments[1].getText().equals("E_USER_DEPRECATED")) {
                        final Function scope = ExpressionSemanticUtil.getScope(reference);
                        if (scope instanceof Method && !scope.isDeprecated()) {
                            final Method method  = (Method) scope;
                            final PhpClass clazz = method.getContainingClass();
                            if (clazz != null && !clazz.isDeprecated() && !this.isTestContext(reference)) {
                                final GroupStatement body = ExpressionSemanticUtil.getGroupStatement(method);
                                if (body != null) {
                                    PsiElement parent = reference.getParent();
                                    parent            = parent instanceof UnaryExpression ? parent.getParent() : parent;
                                    if (OpenapiTypesUtil.isStatementImpl(parent) && parent.getParent() == body) {
                                        final PsiElement nameNode = NamedElementUtil.getNameIdentifier(method);
                                        if (nameNode != null ) {
                                            holder.registerProblem(nameNode, String.format(patternMissingDeprecationTag, method.getName()));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            @Override
            public void visitPhpMethod(@NotNull Method method) {
                if (this.shouldSkipAnalysis(method, StrictnessCategory.STRICTNESS_CATEGORY_ARCHITECTURE)) { return; }

                /* do not process un-reportable classes and interfaces - we are searching real tech. debt here */
                final PhpClass clazz      = method.getContainingClass();
                final PsiElement nameNode = NamedElementUtil.getNameIdentifier(method);
                if (clazz != null && nameNode != null && !clazz.isDeprecated() && !this.isTestContext(method)) {
                    final String methodName          = method.getName();
                    final boolean isMethodDeprecated = method.isDeprecated();

                    final Collection<Supplier<Collection<PhpClass>>> suppliers = new ArrayList<>();
                    suppliers.add(() -> Collections.singletonList(OpenapiResolveUtil.resolveSuperClass(clazz)));
                    suppliers.add(() -> OpenapiResolveUtil.resolveImplementedInterfaces(clazz));
                    suppliers.add(() -> OpenapiResolveUtil.resolveImplementedTraits(clazz));

                    for (final Supplier<Collection<PhpClass>> supplier : suppliers) {
                        for (final PhpClass contract : supplier.get()) {
                            if (contract != null) {
                                final Method contractMethod = OpenapiResolveUtil.resolveMethod(contract, methodName);
                                if (contractMethod != null) {
                                    if (!isMethodDeprecated && contractMethod.isDeprecated()) {
                                        holder.registerProblem(nameNode, String.format(patternNeedsDeprecation, methodName));
                                        return;
                                    } else if (isMethodDeprecated && !contractMethod.isDeprecated()) {
                                        holder.registerProblem(nameNode, String.format(patternDeprecateParent, methodName));
                                        return;
                                    }
                                }
                            }
                        }
                    }

                    suppliers.clear();
                }
            }
        };
    }
}

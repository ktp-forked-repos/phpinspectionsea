package com.kalessil.phpStorm.phpInspectionsEA.inspectors.semanticalAnalysis.parameters.strategy;

import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.elements.BinaryExpression;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.UnaryExpression;
import com.kalessil.phpStorm.phpInspectionsEA.utils.OpenapiResolveUtil;
import com.kalessil.phpStorm.phpInspectionsEA.utils.Types;
import com.kalessil.phpStorm.phpInspectionsEA.utils.hierarhy.InterfacesExtractUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;

final public class InstanceOfCorrectnessStrategy {

    public static boolean apply(@NotNull ProblemsHolder holder, @NotNull Set<String> parameterTypes, @NotNull PsiElement context) {
        boolean result = false;
        if (context instanceof BinaryExpression) {
            final BinaryExpression binary = (BinaryExpression) context;
            final PsiElement left         = binary.getLeftOperand();
            final PsiElement right        = binary.getRightOperand();
            if (left != null && right instanceof ClassReference && binary.getOperationType() == PhpTokenTypes.kwINSTANCEOF) {
                final boolean isObject = parameterTypes.stream().anyMatch(t -> t.startsWith("\\") || t.equals(Types.strObject));
                if (isObject) {
                    final PsiElement resolved = OpenapiResolveUtil.resolveReference((ClassReference) right);
                    if (resolved instanceof PhpClass) {
                        final int typesCount     = parameterTypes.size();
                        final String checkedType = ((PhpClass) resolved).getFQN();
                        if (parameterTypes.contains(checkedType)) {
                            if (typesCount == 1) {
                                holder.registerProblem(context, "It seems to be always true (same object type).");
                                result = true;
                            } else if (typesCount == 2 && parameterTypes.contains(Types.strNull)) {
                                final PsiElement target  = context.getParent() instanceof UnaryExpression ? context.getParent() : context;
                                final String replacement = String.format("%s %s null", left.getText(), context == target ? "===" : "!==");
                                holder.registerProblem(target, String.format("'%s' can be used instead.", replacement));
                                result = true;
                            }
                        } else {
                            if (typesCount == 1) {
                                final Collection<PhpClass> classes = OpenapiResolveUtil.resolveClassesByFQN(
                                        parameterTypes.iterator().next(),
                                        PhpIndex.getInstance(holder.getProject())
                                );
                                if (classes.size() == 1) {
                                    final Set<PhpClass> parents = InterfacesExtractUtil.getCrawlInheritanceTree(classes.iterator().next(), true);
                                    if (parents.contains(resolved)) {
                                        holder.registerProblem(context, "It seems to be always false (classes are not related).");
                                        result = true;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    holder.registerProblem(context, "It seems to be always false (no object types).");
                    result = true;
                }
            }
        }
        return result;
    }

}
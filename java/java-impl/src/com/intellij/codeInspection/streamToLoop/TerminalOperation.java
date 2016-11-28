/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.streamToLoop;

import com.intellij.codeInspection.streamToLoop.StreamToLoopInspection.StreamToLoopReplacementContext;
import com.intellij.codeInspection.util.OptionalUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.siyeh.ig.psiutils.BoolUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * @author Tagir Valeev
 */
abstract class TerminalOperation extends Operation {
  @Override
  final String wrap(StreamVariable inVar, StreamVariable outVar, String code, StreamToLoopReplacementContext context) {
    return generate(inVar, context);
  }

  @Override
  final void rename(String oldName, String newName, StreamToLoopReplacementContext context) {
    throw new IllegalStateException("Should not be called for terminal operation (tried to rename " + oldName + " -> " + newName + ")");
  }

  @Override
  final boolean changesVariable() {
    return true;
  }

  CollectorOperation asCollector() {
    return null;
  }

  abstract String generate(StreamVariable inVar, StreamToLoopReplacementContext context);

  @Nullable
  static TerminalOperation createTerminal(@NotNull String name, @NotNull PsiExpression[] args,
                                          @NotNull PsiType elementType, @NotNull PsiType resultType, boolean isVoid) {
    if(isVoid) {
      if ((name.equals("forEach") || name.equals("forEachOrdered")) && args.length == 1) {
        FunctionHelper fn = FunctionHelper.create(args[0], 1);
        return fn == null ? null : new ForEachTerminalOperation(fn);
      }
      return null;
    }
    if(name.equals("count") && args.length == 0) {
      return new AccumulatedTerminalOperation("count", "long", "0", "{acc}++;");
    }
    if(name.equals("sum") && args.length == 0) {
      return AccumulatedTerminalOperation.summing(resultType);
    }
    if(name.equals("average") && args.length == 0) {
      if(elementType.equals(PsiType.DOUBLE)) {
        return new AverageTerminalOperation(true, true);
      }
      else if(elementType.equals(PsiType.INT) || elementType.equals(PsiType.LONG)) {
        return new AverageTerminalOperation(false, true);
      }
    }
    if(name.equals("summaryStatistics") && args.length == 0) {
      return AccumulatedTerminalOperation.summarizing(resultType);
    }
    if((name.equals("findFirst") || name.equals("findAny")) && args.length == 0) {
      return new FindTerminalOperation(resultType.getCanonicalText());
    }
    if((name.equals("anyMatch") || name.equals("allMatch") || name.equals("noneMatch")) && args.length == 1) {
      FunctionHelper fn = FunctionHelper.create(args[0], 1);
      return fn == null ? null : new MatchTerminalOperation(fn, name);
    }
    if(name.equals("reduce")) {
      if(args.length == 2 || args.length == 3) {
        FunctionHelper fn = FunctionHelper.create(args[1], 2);
        if(fn != null) {
          return new ReduceTerminalOperation(args[0], fn, resultType.getCanonicalText());
        }
      }
      if(args.length == 1) {
        return ReduceToOptionalTerminalOperation.create(args[0], resultType);
      }
    }
    if(name.equals("toArray") && args.length < 2) {
      if(!(resultType instanceof PsiArrayType)) return null;
      PsiType componentType = ((PsiArrayType)resultType).getComponentType();
      if (componentType instanceof PsiPrimitiveType) {
        if(args.length == 0) return new ToPrimitiveArrayTerminalOperation(componentType.getCanonicalText());
      }
      else {
        String arr = "";
        if(args.length == 1) {
          if(!(args[0] instanceof PsiMethodReferenceExpression)) return null;
          PsiMethodReferenceExpression arrCtor = (PsiMethodReferenceExpression)args[0];
          if(!arrCtor.isConstructor()) return null;
          PsiTypeElement typeElement = arrCtor.getQualifierType();
          if(typeElement == null) return null;
          PsiType type = typeElement.getType();
          if(!(type instanceof PsiArrayType)) return null;
          arr = "new "+type.getCanonicalText().replaceFirst("\\[]", "[0]");
        }
        return new AccumulatedTerminalOperation("list", CommonClassNames.JAVA_UTIL_LIST + "<" + elementType.getCanonicalText() + ">",
                                                "new "+ CommonClassNames.JAVA_UTIL_ARRAY_LIST+"<>()", "{acc}.add({item});",
                                                "{acc}.toArray("+arr+")");
      }
    }
    if ((name.equals("max") || name.equals("min")) && args.length < 2) {
      return MinMaxTerminalOperation.create(args.length == 1 ? args[0] : null, elementType.getCanonicalText(), name.equals("max"));
    }
    if (name.equals("collect")) {
      if (args.length == 3) {
        FunctionHelper supplier = FunctionHelper.create(args[0], 0);
        if (supplier == null) return null;
        FunctionHelper accumulator = FunctionHelper.create(args[1], 2);
        if (accumulator == null) return null;
        return new ExplicitCollectTerminalOperation(supplier, accumulator);
      }
      if (args.length == 1) {
        return fromCollector(elementType.getCanonicalText(), resultType, args[0]);
      }
    }
    return null;
  }

  @Contract("_, _, null -> null")
  @Nullable
  private static TerminalOperation fromCollector(@NotNull String elementType, @NotNull PsiType resultType, PsiExpression expr) {
    if (!(expr instanceof PsiMethodCallExpression)) return null;
    PsiMethodCallExpression collectorCall = (PsiMethodCallExpression)expr;
    PsiExpression[] collectorArgs = collectorCall.getArgumentList().getExpressions();
    PsiMethod collector = collectorCall.resolveMethod();
    if (collector == null) return null;
    PsiClass collectorClass = collector.getContainingClass();
    if (collectorClass != null && CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS.equals(collectorClass.getQualifiedName())) {
      return fromCollector(elementType, resultType, collector, collectorArgs);
    }
    return null;
  }

  @Nullable
  private static TerminalOperation fromCollector(@NotNull String elementType,
                                                 @NotNull PsiType resultType,
                                                 PsiMethod collector,
                                                 PsiExpression[] collectorArgs) {
    String collectorName = collector.getName();
    FunctionHelper fn;
    switch (collectorName) {
      case "toList":
        if (collectorArgs.length != 0) return null;
        return AccumulatedTerminalOperation.toList(resultType);
      case "toSet":
        if (collectorArgs.length != 0) return null;
        return AccumulatedTerminalOperation.toCollection(resultType, CommonClassNames.JAVA_UTIL_HASH_SET, "set");
      case "toCollection":
        if (collectorArgs.length != 1) return null;
        fn = FunctionHelper.create(collectorArgs[0], 0);
        return fn == null ? null : new ToCollectionTerminalOperation(fn);
      case "toMap": {
        if (collectorArgs.length < 2 || collectorArgs.length > 4) return null;
        FunctionHelper key = FunctionHelper.create(collectorArgs[0], 1);
        FunctionHelper value = FunctionHelper.create(collectorArgs[1], 1);
        if(key == null || value == null) return null;
        PsiExpression merger = collectorArgs.length > 2 ? collectorArgs[2] : null;
        FunctionHelper supplier = collectorArgs.length == 4
                   ? FunctionHelper.create(collectorArgs[3], 0)
                   : FunctionHelper.hashMapSupplier(resultType);
        if(supplier == null) return null;
        return new ToMapTerminalOperation(key, value, merger, supplier, resultType);
      }
      case "reducing":
        switch (collectorArgs.length) {
          case 1:
            return ReduceToOptionalTerminalOperation.create(collectorArgs[0], resultType);
          case 2:
            fn = FunctionHelper.create(collectorArgs[1], 2);
            return fn == null ? null : new ReduceTerminalOperation(collectorArgs[0], fn, resultType.getCanonicalText());
          case 3:
            FunctionHelper mapper = FunctionHelper.create(collectorArgs[1], 1);
            fn = FunctionHelper.create(collectorArgs[2], 2);
            return fn == null || mapper == null
                   ? null
                   : new MappingTerminalOperation(mapper, new ReduceTerminalOperation(collectorArgs[0], fn, resultType.getCanonicalText()));
        }
        return null;
      case "counting":
        if (collectorArgs.length != 0) return null;
        return new AccumulatedTerminalOperation("count", "long", "0", "{acc}++;");
      case "summingInt":
      case "summingLong":
      case "summingDouble": {
        if (collectorArgs.length != 1) return null;
        fn = FunctionHelper.create(collectorArgs[0], 1);
        PsiPrimitiveType type = PsiPrimitiveType.getUnboxedType(resultType);
        return fn == null || type == null ? null : new InlineMappingTerminalOperation(fn, AccumulatedTerminalOperation.summing(type));
      }
      case "summarizingInt":
      case "summarizingLong":
      case "summarizingDouble": {
        if (collectorArgs.length != 1) return null;
        fn = FunctionHelper.create(collectorArgs[0], 1);
        return fn == null ? null : new InlineMappingTerminalOperation(fn, AccumulatedTerminalOperation.summarizing(resultType));
      }
      case "averagingInt":
      case "averagingLong":
      case "averagingDouble": {
        if (collectorArgs.length != 1) return null;
        fn = FunctionHelper.create(collectorArgs[0], 1);
        return fn == null
               ? null
               : new InlineMappingTerminalOperation(fn, new AverageTerminalOperation(collectorName.equals("averagingDouble"), false));
      }
      case "mapping": {
        if (collectorArgs.length != 2) return null;
        fn = FunctionHelper.create(collectorArgs[0], 1);
        if (fn == null) return null;
        TerminalOperation downstreamOp = fromCollector(fn.getResultType(), resultType, collectorArgs[1]);
        return downstreamOp == null ? null : new MappingTerminalOperation(fn, downstreamOp);
      }
      case "groupingBy":
      case "partitioningBy": {
        if (collectorArgs.length == 0 || collectorArgs.length > 3
            || collectorArgs.length == 3 && collectorName.equals("partitioningBy")) return null;
        fn = FunctionHelper.create(collectorArgs[0], 1);
        if (fn == null) return null;
        if (!(resultType instanceof PsiClassType)) return null;
        PsiClass aClass = ((PsiClassType)resultType).resolve();
        if (aClass == null) return null;
        PsiSubstitutor substitutor = ((PsiClassType)resultType).resolveGenerics().getSubstitutor();
        PsiClass mapClass =
          JavaPsiFacade.getInstance(aClass.getProject()).findClass(CommonClassNames.JAVA_UTIL_MAP, aClass.getResolveScope());
        if (mapClass == null) return null;
        PsiTypeParameter[] parameters = mapClass.getTypeParameters();
        if (parameters.length != 2) return null;
        PsiType resultSubType = substitutor.substitute(parameters[1]);
        if (resultSubType == null) return null;
        CollectorOperation downstreamCollector;
        if (collectorArgs.length == 1) {
          downstreamCollector = AccumulatedTerminalOperation.toList(resultSubType).asCollector();
        }
        else {
          PsiExpression downstream = collectorArgs[collectorArgs.length - 1];
          TerminalOperation downstreamOp = fromCollector(elementType, resultSubType, downstream);
          if (downstreamOp == null) return null;
          downstreamCollector = downstreamOp.asCollector();
        }
        if (downstreamCollector == null) return null;
        if (collectorName.equals("partitioningBy")) {
          return new PartitionByTerminalOperation(fn, resultType, downstreamCollector);
        }
        FunctionHelper supplier = collectorArgs.length == 3
                                  ? FunctionHelper.create(collectorArgs[1], 0)
                                  : FunctionHelper.hashMapSupplier(resultType);
        return new GroupByTerminalOperation(fn, supplier, resultType, downstreamCollector);
      }
      case "minBy":
      case "maxBy":
        if (collectorArgs.length != 1) return null;
        return MinMaxTerminalOperation.create(collectorArgs[0], elementType, collectorName.equals("maxBy"));
      case "joining":
        switch (collectorArgs.length) {
          case 0:
            return new AccumulatedTerminalOperation("sb", CommonClassNames.JAVA_LANG_STRING_BUILDER,
                                                    "new " + CommonClassNames.JAVA_LANG_STRING_BUILDER + "()",
                                                    "{acc}.append({item});",
                                                    "{acc}.toString()");
          case 1:
          case 3:
            String initializer =
              "new java.util.StringJoiner(" + StreamEx.of(collectorArgs).map(PsiElement::getText).joining(",") + ")";
            return new AccumulatedTerminalOperation("joiner", "java.util.StringJoiner", initializer,
                                                    "{acc}.add({item});", "{acc}.toString()");
        }
        return null;
    }
    return null;
  }

  static class ReduceTerminalOperation extends TerminalOperation {
    private PsiExpression myIdentity;
    private String myType;
    private FunctionHelper myUpdater;

    public ReduceTerminalOperation(PsiExpression identity, FunctionHelper updater, String type) {
      myIdentity = identity;
      myType = type;
      myUpdater = updater;
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      FunctionHelper.processUsedNames(myIdentity, usedNameConsumer);
      myUpdater.registerUsedNames(usedNameConsumer);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String accumulator = context.declareResult("acc", myType, myIdentity.getText());
      myUpdater.transform(context, accumulator, inVar.getName());
      return accumulator + "=" + myUpdater.getText() + ";";
    }
  }

  static class ReduceToOptionalTerminalOperation extends TerminalOperation {
    private String myType;
    private FunctionHelper myUpdater;

    public ReduceToOptionalTerminalOperation(FunctionHelper updater, String type) {
      myType = type;
      myUpdater = updater;
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      myUpdater.registerUsedNames(usedNameConsumer);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String seen = context.declare("seen", "boolean", "false");
      String accumulator = context.declareResult("acc", myType, TypeConversionUtil.isPrimitive(myType) ? "0" : "null");
      myUpdater.transform(context, accumulator, inVar.getName());
      context.setOptionalUnwrapperFinisher(seen, accumulator, myType);
      return "if(!" + seen + ") {\n" +
             seen + "=true;\n" +
             accumulator + "=" + inVar + ";\n" +
             "} else {\n" +
             accumulator + "=" + myUpdater.getText() + ";\n" +
             "}\n";
    }

    @Nullable
    static ReduceToOptionalTerminalOperation create(PsiExpression arg, PsiType resultType) {
      PsiType optionalElementType = OptionalUtil.getOptionalElementType(resultType);
      FunctionHelper fn = FunctionHelper.create(arg, 2);
      if(fn != null && optionalElementType != null) {
        return new ReduceToOptionalTerminalOperation(fn, optionalElementType.getCanonicalText());
      }
      return null;
    }
  }

  static class ExplicitCollectTerminalOperation extends TerminalOperation {
    private final FunctionHelper mySupplier;
    private final FunctionHelper myAccumulator;

    public ExplicitCollectTerminalOperation(FunctionHelper supplier, FunctionHelper accumulator) {
      mySupplier = supplier;
      myAccumulator = accumulator;
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      mySupplier.registerUsedNames(usedNameConsumer);
      myAccumulator.registerUsedNames(usedNameConsumer);
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myAccumulator.suggestVariableName(inVar, 1);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      mySupplier.transform(context);
      String candidate = mySupplier.suggestFinalOutputNames(context, myAccumulator.getParameterName(0), "acc").get(0);
      String acc = context.declareResult(candidate, mySupplier.getResultType(), mySupplier.getText());
      myAccumulator.transform(context, acc, inVar.getName());
      return myAccumulator.getText()+";\n";
    }
  }

  static class AverageTerminalOperation extends TerminalOperation {
    private final boolean myDoubleAccumulator;
    private final boolean myUseOptional;

    public AverageTerminalOperation(boolean doubleAccumulator, boolean useOptional) {
      myDoubleAccumulator = doubleAccumulator;
      myUseOptional = useOptional;
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String sum = context.declareResult("sum", myDoubleAccumulator ? "double" : "long", "0");
      String count = context.declare("count", "long", "0");
      String emptyCheck = count + "==0";
      String result = (myDoubleAccumulator ? "" : "(double)") + sum + "/" + count;
      context.setFinisher(myUseOptional
                        ? "(" + emptyCheck + "?java.util.OptionalDouble.empty():"
                          + "java.util.OptionalDouble.of(" + result + "))"
                        : "(" + emptyCheck + "?0.0:" + result + ")");
      return sum + "+=" + inVar + ";\n" + count + "++;\n";
    }
  }

  static class ToPrimitiveArrayTerminalOperation extends TerminalOperation {
    private String myType;

    ToPrimitiveArrayTerminalOperation(String type) {
      myType = type;
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String arr = context.declareResult("arr", myType + "[]", "new " + myType + "[10]");
      String count = context.declare("count", "int", "0");
      context.setFinisher("java.util.Arrays.copyOfRange("+arr+",0,"+count+")");
      return "if(" + arr + ".length==" + count + ") " + arr + "=java.util.Arrays.copyOf(" + arr + "," + count + "*2);\n" +
             arr + "[" + count + "++]=" + inVar + ";\n";
    }
  }

  static class FindTerminalOperation extends TerminalOperation {
    private String myType;

    public FindTerminalOperation(String type) {
      myType = type;
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      int pos = myType.indexOf('<');
      String optType = pos == -1 ? myType : myType.substring(0, pos);
      return context.assignAndBreak("found", myType, optType + ".of(" + inVar + ")", optType + ".empty()");
    }
  }

  static class MatchTerminalOperation extends TerminalOperation {
    private final FunctionHelper myFn;
    private final String myName;
    private final boolean myDefaultValue, myNegatePredicate;

    public MatchTerminalOperation(FunctionHelper fn, String name) {
      myFn = fn;
      switch(name) {
        case "anyMatch":
          myName = "found";
          myDefaultValue = false;
          myNegatePredicate = false;
          break;
        case "allMatch":
          myName = "allMatch";
          myDefaultValue = true;
          myNegatePredicate = true;
          break;
        case "noneMatch":
          myName = "noneMatch";
          myDefaultValue = true;
          myNegatePredicate = false;
          break;
        default:
          throw new IllegalArgumentException(name);
      }
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      myFn.registerUsedNames(usedNameConsumer);
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myFn.suggestVariableName(inVar, 0);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      myFn.transform(context, inVar.getName());
      String expression;
      if (myNegatePredicate) {
        PsiLambdaExpression lambda = (PsiLambdaExpression)context.createExpression("(" + inVar.getDeclaration() + ")->" + myFn.getText());
        expression = BoolUtils.getNegatedExpressionText((PsiExpression)lambda.getBody());
      }
      else {
        expression = myFn.getText();
      }
      return "if(" + expression + ") {\n" +
             context.assignAndBreak(myName, PsiType.BOOLEAN.getCanonicalText(), String.valueOf(!myDefaultValue), String.valueOf(myDefaultValue)) +
             "}\n";
    }
  }

  interface CollectorOperation {
    // Non-trivial finishers are not supported
    default void transform(StreamToLoopReplacementContext context, String item) {}
    default void suggestNames(StreamVariable inVar, StreamVariable outVar) {}
    default void registerUsedNames(Consumer<String> usedNameConsumer) {}
    String getSupplier();
    String getAccumulator(String acc, String item);
  }

  abstract static class CollectorBasedTerminalOperation extends TerminalOperation implements CollectorOperation {
    final String myType;
    final Function<StreamToLoopReplacementContext, String> myAccNameSupplier;
    final FunctionHelper mySupplier;

    CollectorBasedTerminalOperation(String type, Function<StreamToLoopReplacementContext, String> accNameSupplier,
                                    FunctionHelper accSupplier) {
      myType = type;
      myAccNameSupplier = accNameSupplier;
      mySupplier = accSupplier;
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      transform(context, inVar.getName());
      String acc = context.declareResult(myAccNameSupplier.apply(context), myType, getSupplier());
      return getAccumulator(acc, inVar.getName());
    }

    @Override
    CollectorOperation asCollector() {
      return this;
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      mySupplier.registerUsedNames(usedNameConsumer);
    }

    @Override
    public void transform(StreamToLoopReplacementContext context, String item) {
      mySupplier.transform(context);
    }

    @Override
    public String getSupplier() {
      return mySupplier.getText();
    }
  }

  static class AccumulatedTerminalOperation extends TerminalOperation implements CollectorOperation {
    private String myAccName;
    private String myAccType;
    private String myAccInitializer;
    private String myUpdateTemplate;
    private String myFinisherTemplate;

    /**
     * @param accName desired name for accumulator variable
     * @param accType type of accumulator variable
     * @param accInitializer initializer for accumulator variable
     * @param updateTemplate template to update accumulator. May contain {@code {acc}} - reference to accumulator variable
     *                       and {@code {item}} - reference to stream element.
     * @param finisherTemplate template to final result. May contain {@code {acc}} - reference to accumulator variable.
     *                         By default it's {@code "{acc}"}
     */
    AccumulatedTerminalOperation(String accName, String accType, String accInitializer, String updateTemplate, String finisherTemplate) {
      myAccName = accName;
      myAccType = accType;
      myAccInitializer = accInitializer;
      myUpdateTemplate = updateTemplate;
      myFinisherTemplate = finisherTemplate;
    }

    AccumulatedTerminalOperation(String accName, String accType, String accInitializer, String updateTemplate) {
      this(accName, accType, accInitializer, updateTemplate, "{acc}");
    }

    @Override
    public String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String varName = context.declareResult(myAccName, myAccType, myAccInitializer);
      context.setFinisher(myFinisherTemplate.replace("{acc}", varName));
      return myUpdateTemplate.replace("{item}", inVar.getName()).replace("{acc}", varName);
    }

    @Override
    CollectorOperation asCollector() {
      return myFinisherTemplate.equals("{acc}") && !TypeConversionUtil.isPrimitive(myAccType) ? this : null;
    }

    @Override
    public String getSupplier() {
      return myAccInitializer;
    }

    @Override
    public String getAccumulator(String acc, String item) {
      return myUpdateTemplate.replace("{acc}", acc).replace("{item}", item);
    }

    @NotNull
    static AccumulatedTerminalOperation toCollection(PsiType collectionType, String implementationType, String varName) {
      return new AccumulatedTerminalOperation(varName, collectionType.getCanonicalText(), "new " + implementationType + "<>()",
                                              "{acc}.add({item});");
    }

    @NotNull
    private static AccumulatedTerminalOperation toList(@NotNull PsiType resultType) {
      return toCollection(resultType, CommonClassNames.JAVA_UTIL_ARRAY_LIST, "list");
    }

    @NotNull
    static AccumulatedTerminalOperation summing(PsiType type) {
      return new AccumulatedTerminalOperation("sum", type.getCanonicalText(), "0", "{acc}+={item};");
    }

    @NotNull
    static AccumulatedTerminalOperation summarizing(@NotNull PsiType resultType) {
      return new AccumulatedTerminalOperation("stat", resultType.getCanonicalText(), "new " + resultType.getCanonicalText() + "()",
                                              "{acc}.accept({item});");
    }
  }

  static class ToCollectionTerminalOperation extends CollectorBasedTerminalOperation {
    public ToCollectionTerminalOperation(FunctionHelper fn) {
      super(fn.getResultType(), context -> fn.suggestFinalOutputNames(context, null, "collection").get(0), fn);
    }

    @Override
    public String getAccumulator(String acc, String item) {
      return acc+".add("+item+");\n";
    }
  }

  static class MinMaxTerminalOperation extends TerminalOperation {
    private String myType;
    private String myTemplate;
    private String myComparatorType;
    private @Nullable PsiExpression myComparator;

    public MinMaxTerminalOperation(String type, String template, @Nullable PsiExpression comparator) {
      myType = type;
      myTemplate = template;
      myComparator = comparator;
      if(comparator != null) {
        PsiType comparatorType = comparator.getType();
        if(comparatorType != null) {
          myComparatorType = comparatorType.getCanonicalText();
        } else {
          myComparatorType = CommonClassNames.JAVA_UTIL_COMPARATOR+"<"+myType+">";
        }
      }
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      if(myComparator != null) {
        FunctionHelper.processUsedNames(myComparator, usedNameConsumer);
      }
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String comparator = "";
      if(myComparator != null) {
        if(ExpressionUtils.isSimpleExpression(myComparator)) {
          comparator = myComparator.getText();
        } else {
          comparator = context.declare("comparator", myComparatorType, myComparator.getText());
        }
      }
      String seen = context.declare("seen", "boolean", "false");
      String best = context.declareResult("best", myType, TypeConversionUtil.isPrimitive(myType) ? "0" : "null");
      String type = myType;
      context.setOptionalUnwrapperFinisher(seen, best, type);
      return "if(!"+seen+" || "+myTemplate.replace("{best}", best).replace("{item}", inVar.getName()).replace("{comparator}", comparator)+") {\n" +
             seen+"=true;\n"+
             best+"="+inVar+";\n}\n";
    }

    @Nullable
    static MinMaxTerminalOperation create(@Nullable PsiExpression comparator, String elementType, boolean max) {
      String sign = max ? ">" : "<";
      if(comparator == null) {
        if (PsiType.INT.equalsToText(elementType) || PsiType.LONG.equalsToText(elementType)) {
          return new MinMaxTerminalOperation(elementType, "{item}" + sign + "{best}", null);
        }
        if (PsiType.DOUBLE.equalsToText(elementType)) {
          return new MinMaxTerminalOperation(elementType, "java.lang.Double.compare({item},{best})" + sign + "0", null);
        }
      } else if(InheritanceUtil.isInheritor(PsiUtil.resolveClassInClassTypeOnly(comparator.getType()), false,
                                     CommonClassNames.JAVA_UTIL_COMPARATOR)) {
        return new MinMaxTerminalOperation(elementType, "{comparator}.compare({item},{best})" + sign + "0", comparator);
      }
      return null;
    }
  }

  static class ToMapTerminalOperation extends CollectorBasedTerminalOperation {
    private final FunctionHelper myKeyExtractor, myValueExtractor;
    private final PsiExpression myMerger;

    ToMapTerminalOperation(FunctionHelper keyExtractor,
                           FunctionHelper valueExtractor,
                           PsiExpression merger,
                           FunctionHelper supplier,
                           PsiType resultType) {
      super(resultType.getCanonicalText(), context -> "map", supplier);
      myKeyExtractor = keyExtractor;
      myValueExtractor = valueExtractor;
      myMerger = merger;
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      super.registerUsedNames(usedNameConsumer);
      myKeyExtractor.registerUsedNames(usedNameConsumer);
      myValueExtractor.registerUsedNames(usedNameConsumer);
      if(myMerger != null) FunctionHelper.processUsedNames(myMerger, usedNameConsumer);
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myKeyExtractor.suggestVariableName(inVar, 0);
      myValueExtractor.suggestVariableName(inVar, 0);
    }

    @Override
    public void transform(StreamToLoopReplacementContext context, String item) {
      super.transform(context, item);
      myKeyExtractor.transform(context, item);
      myValueExtractor.transform(context, item);
    }

    @Override
    public String getAccumulator(String map, String item) {
      if(myMerger == null) {
        return "if("+map+".put("+myKeyExtractor.getText()+","+myValueExtractor.getText()+")!=null) {\n"+
               "throw new java.lang.IllegalStateException(\"Duplicate key\");\n}\n";
      }
      return map+".merge("+myKeyExtractor.getText()+","+myValueExtractor.getText()+","+myMerger.getText()+");\n";
    }
  }

  static class GroupByTerminalOperation extends CollectorBasedTerminalOperation {
    private final CollectorOperation myCollector;
    private FunctionHelper myKeyExtractor;
    private String myKeyVar;

    public GroupByTerminalOperation(FunctionHelper keyExtractor, FunctionHelper supplier, PsiType resultType, CollectorOperation collector) {
      super(resultType.getCanonicalText(), context -> "map", supplier);
      myKeyExtractor = keyExtractor;
      myCollector = collector;
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      super.registerUsedNames(usedNameConsumer);
      myKeyExtractor.registerUsedNames(usedNameConsumer);
      myCollector.registerUsedNames(usedNameConsumer);
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myKeyExtractor.suggestVariableName(inVar, 0);
      myCollector.suggestNames(inVar, outVar);
    }

    @Override
    public void transform(StreamToLoopReplacementContext context, String item) {
      super.transform(context, item);
      myKeyExtractor.transform(context, item);
      myCollector.transform(context, item);
      myKeyVar = context.registerVarName(Arrays.asList("k", "key"));
    }

    @Override
    public String getAccumulator(String map, String item) {
      String acc = map+".computeIfAbsent("+myKeyExtractor.getText()+","+myKeyVar+"->"+myCollector.getSupplier()+")";
      return myCollector.getAccumulator(acc, item);
    }
  }

  static class PartitionByTerminalOperation extends TerminalOperation {
    private final String myResultType;
    private final CollectorOperation myCollector;
    private FunctionHelper myPredicate;

    public PartitionByTerminalOperation(FunctionHelper predicate, PsiType resultType, CollectorOperation collector) {
      myPredicate = predicate;
      myResultType = resultType.getCanonicalText();
      myCollector = collector;
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      myPredicate.registerUsedNames(usedNameConsumer);
      myCollector.registerUsedNames(usedNameConsumer);
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myPredicate.suggestVariableName(inVar, 0);
      myCollector.suggestNames(inVar, outVar);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String map = context.declareResult("map", myResultType, "new java.util.HashMap<>()");
      myPredicate.transform(context, inVar.getName());
      myCollector.transform(context, inVar.getName());
      context.addInitStep(map+".put(false, "+myCollector.getSupplier()+");");
      context.addInitStep(map+".put(true, "+myCollector.getSupplier()+");");
      return myCollector.getAccumulator(map + ".get(" + myPredicate.getText() + ")", inVar.getName());
    }
  }

  abstract static class AbstractMappingTerminalOperation extends TerminalOperation implements CollectorOperation {
    final FunctionHelper myMapper;
    final TerminalOperation myDownstream;
    final CollectorOperation myDownstreamCollector;

    AbstractMappingTerminalOperation(FunctionHelper mapper, TerminalOperation downstream) {
      myMapper = mapper;
      myDownstream = downstream;
      myDownstreamCollector = downstream.asCollector();
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      myMapper.registerUsedNames(usedNameConsumer);
      myDownstream.registerUsedNames(usedNameConsumer);
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myMapper.suggestVariableName(inVar, 0);
    }

    @Override
    CollectorOperation asCollector() {
      return myDownstreamCollector == null ? null : this;
    }

    @Override
    public String getSupplier() {
      return myDownstreamCollector.getSupplier();
    }
  }

  static class MappingTerminalOperation extends AbstractMappingTerminalOperation {
    private StreamVariable myVariable;

    MappingTerminalOperation(FunctionHelper mapper, TerminalOperation downstream) {
      super(mapper, downstream);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      createVariable(context, inVar.getName());
      return myVariable.getDeclaration() + "=" + myMapper.getText() + ";\n" + myDownstream.generate(myVariable, context);
    }

    private void createVariable(StreamToLoopReplacementContext context, String item) {
      myMapper.transform(context, item);
      myVariable = new StreamVariable(myMapper.getResultType());
      myDownstream.suggestNames(myVariable, StreamVariable.STUB);
      myMapper.suggestFinalOutputNames(context, null, null).forEach(myVariable::addOtherNameCandidate);
      myVariable.register(context);
    }

    @Override
    public void transform(StreamToLoopReplacementContext context, String item) {
      createVariable(context, item);
      myDownstreamCollector.transform(context, myVariable.getName());
    }

    @Override
    public String getAccumulator(String acc, String item) {
      return myVariable.getDeclaration() + "=" + myMapper.getText() + ";\n" +
             myDownstreamCollector.getAccumulator(acc, myVariable.getName());
    }
  }

  static class InlineMappingTerminalOperation extends AbstractMappingTerminalOperation {
    InlineMappingTerminalOperation(FunctionHelper mapper, TerminalOperation downstream) {
      super(mapper, downstream);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      myMapper.transform(context, inVar.getName());
      StreamVariable updatedVar = new StreamVariable(myMapper.getResultType(), myMapper.getText());
      return myDownstream.generate(updatedVar, context);
    }

    @Override
    public void transform(StreamToLoopReplacementContext context, String item) {
      myMapper.transform(context, item);
      myDownstreamCollector.transform(context, myMapper.getText());
    }

    @Override
    public String getAccumulator(String acc, String item) {
      return myDownstreamCollector.getAccumulator(acc, myMapper.getText());
    }
  }

  static class ForEachTerminalOperation extends TerminalOperation {
    private FunctionHelper myFn;

    public ForEachTerminalOperation(FunctionHelper fn) {
      myFn = fn;
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myFn.suggestVariableName(inVar, 0);
    }

    @Override
    public void registerUsedNames(Consumer<String> usedNameConsumer) {
      myFn.registerUsedNames(usedNameConsumer);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      myFn.transform(context, inVar.getName());
      return myFn.getText()+";\n";
    }
  }
}

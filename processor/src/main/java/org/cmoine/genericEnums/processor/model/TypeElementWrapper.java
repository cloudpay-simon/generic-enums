package org.cmoine.genericEnums.processor.model;

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.function.Predicate;
import org.cmoine.genericEnums.GenericEnum;
import org.cmoine.genericEnums.GenericEnumOuterClass;
import org.cmoine.genericEnums.processor.util.TreeUtil;

import javax.lang.model.element.*;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class TypeElementWrapper {
//    final Trees trees;
    private final TypeElement typeElement;
    private final List<EnumConstantTreeWrapper> enumConstantTree;
    private final Set<String> genericParameterNames;
    private final List<MethodTreeWrapper> methodTree;
    private final List<ConstructorTreeWrapper> constructorTree;
    private final List<FieldTreeWrapper> fieldTree;
    CompilationUnitTree compilationUnitTree;
    ClassTree classTree;

    // https://stackoverflow.com/questions/6373145/accessing-source-code-from-java-annotation-processor
    private class CodeAnalyzerTreeScanner extends TreePathScanner<Object, Trees> {
        @Override
        public Object visitClass(ClassTree node, Trees trees) {
            if(node.getSimpleName().equals(typeElement.getSimpleName())) {
                if(classTree!=null)
                    throw new IllegalArgumentException("Several matches:\n"+classTree+"\nAND\n"+node);
                classTree=node;
            }
            return super.visitClass(node, trees);
        }
    }

    public TypeElementWrapper(Trees trees, TypeElement typeElement) {
//        this.trees = trees;
        this.typeElement = typeElement;

        CodeAnalyzerTreeScanner codeScanner = new CodeAnalyzerTreeScanner();
        TreePath tp = trees.getPath(typeElement);
        compilationUnitTree = tp.getCompilationUnit();
        codeScanner.scan(tp, trees);
        if(classTree==null)
            throw new NullPointerException();

        ImmutableList.Builder<MethodTreeWrapper> methodTreeBuilder=ImmutableList.builder();
        ImmutableList.Builder<ConstructorTreeWrapper> constructorTreeBuilder=ImmutableList.builder();
        ImmutableList.Builder<EnumConstantTreeWrapper> enumConstantTreeBuilder=ImmutableList.builder();
        ImmutableList.Builder<FieldTreeWrapper> fieldTreeBuilder=ImmutableList.builder();
        Set<String> genericParameterNames=null;
        // Process constructors first, as the resulting list is used during processing of other methods.
        for (Tree tree: classTree.getMembers()) {
            if(tree instanceof MethodTree) {
                MethodTree methodDecl= (MethodTree) tree;
                ExecutableElement methodSymbol = (ExecutableElement) TreeUtil.getSymbol(methodDecl);
                if(ElementKind.CONSTRUCTOR.equals(methodSymbol.getKind())) {
                    ConstructorTreeWrapper constructorWrapper = new ConstructorTreeWrapper(this, methodDecl);
                    if(genericParameterNames==null) {
                        genericParameterNames=new TreeSet<>(constructorWrapper.getGenericParameters());
                    } else {
                        String oldGenericParameterNames = toString(genericParameterNames);
                        String newGenericParameterNames = toString(new TreeSet<>(constructorWrapper.getGenericParameters()));
                        if(!oldGenericParameterNames.equals(newGenericParameterNames)) {
                            throw new IllegalArgumentException("All constructors must have the same generic parameters: '"+oldGenericParameterNames+"' != '"+newGenericParameterNames+"' (class="+typeElement.getQualifiedName()+")");
                        }
                    }
                    constructorTreeBuilder.add(constructorWrapper);
                }
            }
        }
        constructorTree=constructorTreeBuilder.build();

        for(Tree tree: classTree.getMembers()) {
            if(tree instanceof MethodTree) {
                MethodTree methodDecl= (MethodTree) tree;
                ExecutableElement methodSymbol = (ExecutableElement) TreeUtil.getSymbol(methodDecl);
                if(ElementKind.METHOD.equals(methodSymbol.getKind())) {
                    if(isValidMethod(methodSymbol)) {
                        MethodTreeWrapper methodWrapper = new MethodTreeWrapper(this, methodDecl, null);
                        methodTreeBuilder.add(methodWrapper);
                    }
                }
            } else if (tree instanceof VariableTree) {
                VariableTree variableDecl= (VariableTree) tree;
                Element sym = TreeUtil.getSymbol(variableDecl);
                if(ElementKind.ENUM_CONSTANT.equals(sym.getKind())) {
                    enumConstantTreeBuilder.add(new EnumConstantTreeWrapper(this, variableDecl));
                } else if(ElementKind.FIELD.equals(sym.getKind())) {
                    fieldTreeBuilder.add(new FieldTreeWrapper(this, variableDecl));
                }
            }
        }
        enumConstantTree=enumConstantTreeBuilder.build();
        methodTree=methodTreeBuilder.build();
        fieldTree=fieldTreeBuilder.build();
        this.genericParameterNames=genericParameterNames;
    }

    private String toString(Set<String> parameters) {
        return parameters.stream().collect(Collectors.joining(", "));
    }

    public List<ConstructorTreeWrapper> getConstructorTree() {
        return constructorTree;
    }

    private boolean isValidMethod(ExecutableElement it) {
        if("values".equals(it.getSimpleName().toString())
                && it.getParameters().size()==0
                && it.getModifiers().contains(Modifier.STATIC))
            return false;

        if("valueOf".equals(it.getSimpleName().toString())
                && it.getParameters().size()==1
                && it.getModifiers().contains(Modifier.STATIC))
            return false;

        return true;
    }

    public boolean isAbstract() {
        return getMethodTree().stream().anyMatch(it -> it.isAbstract());
    }

    /**
     * Should the generated enum be declared 'static'? I.e. Will this pseudo enum class be an inner-class.
     *
     * @return <code>true</code> if the class should be declared 'static', <code>false</code> otherwise.
     */
    public boolean isStatic() {
        return typeElement.getEnclosingElement().getKind() == ElementKind.CLASS;
    }

    /**
     * Get the set of template names for this class or enum.
     * e.g. <code>"T", "U"</code>
     *
     * @return the set of type names used in the declaration of this class.
     */
    public Set<String> getGenericParameterNames() {
        return genericParameterNames;
    }

    /**
     * Get the list of interfaces this class implements. Includes Serializable and Comparable.
     *
     * @return a list of interface names which this class implements.
     */
    public List<String> getInterfaceTree() {
        Set<String> interfaceTreeWrappers = new TreeSet<>();
        if (!isClass()) {
            interfaceTreeWrappers.add("Comparable<" + getClassName() + "<" + String.join(", ", getGenericParameterNames()) + ">>");
        }
        interfaceTreeWrappers.add("Serializable");
        interfaceTreeWrappers.addAll(classTree.getImplementsClause().stream().map(InterfaceTreeWrapper::new).map(Object::toString).collect(Collectors.toList()));
        return new ArrayList<>(interfaceTreeWrappers);
    }

    /**
     * Get the list of import statements this class requires.
     *
     * @return a list of imports list class requires.
     */
    public List<String> getImports() {
        return compilationUnitTree.getImports().stream().map(Object::toString).collect(Collectors.toList());
    }

    public List<MethodTreeWrapper> getMethodTree() {
        return methodTree;
    }

    public List<FieldTreeWrapper> getFieldTree() {
        return fieldTree;
    }

    public List<EnumConstantTreeWrapper> getEnumConstantTree() {
        return enumConstantTree;
    }

    /**
     * Does this type represent a class, as apposed to an enum?
     *
     * @return <code>true</code> if this type represents a class, <code>false</code> if it represents an enum.
     */
    public boolean isClass() {
        return typeElement.getKind() == ElementKind.CLASS;
    }

    /**
     * Calculate the name of the generated class.
     * <ul>
     * <li>If this typeElement is a class, then use the {@link GenericEnumOuterClass} annotation (if present) to calculate the name.</li>
     * <li>If this typeElement is an enum, then use the required {@link GenericEnum} annotation to calculate the name.</li>
     * </ul>
     * @return The name to use for the generated class.
     */
    public String getClassName() {
        switch (typeElement.getKind()) {
            case CLASS:
                final GenericEnumOuterClass genericEnumOuterClass = typeElement.getAnnotation(
                    GenericEnumOuterClass.class);

                if (genericEnumOuterClass != null) {
                    return genericEnumOuterClass.name().replace("%", typeElement.getSimpleName());
                }
                return typeElement.getSimpleName() + "Ext";

            case ENUM:
                return typeElement.getAnnotation(GenericEnum.class).name().replace("%", typeElement.getSimpleName());

            default:
                throw new IllegalArgumentException("Unsupported element " + typeElement.getKind());
        }
    }

    /**
     * Does the original type have a <code>toString()</code> method.
     *
     * @return <code>true</code> if a toString() method is present, <code>false</code> otherwise.
     */
    public boolean isToStringMethodPresent() {
        Predicate<MethodTreeWrapper> isToString = methodTreeWrapper ->
            methodTreeWrapper.getParameters().isEmpty() && methodTreeWrapper.getName().contentEquals("toString");

        return methodTree.stream().anyMatch(isToString);
    }
}

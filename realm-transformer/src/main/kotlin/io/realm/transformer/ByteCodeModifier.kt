/*
 * Copyright 2018 Realm Inc.
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

package io.realm.transformer

import io.realm.annotations.Ignore
import io.realm.annotations.RealmClass
import io.realm.transformer.ext.hasRealmClassAnnotation
import io.realm.transformer.ext.safeSubtypeOf
import javassist.*
import javassist.expr.ExprEditor
import javassist.expr.FieldAccess

/**
 * This class encapsulates the bytecode manipulation code needed to transform model classes
 * and the classes using them.
 */
class BytecodeModifier {

    companion object {
        fun CtField.isModelField(): Boolean =
            !hasAnnotation(Ignore::class.java) &&
                    !Modifier.isTransient(modifiers) &&
                    !Modifier.isStatic(modifiers)

        /**
         * Adds Realm specific accessors to a model class.
         * All the declared fields will be associated with a getter and a setter.
         *
         * @param clazz the CtClass to add accessors to.
         */
        @JvmStatic
        fun addRealmAccessors(clazz: CtClass) {
            val methods: List<String> = clazz.declaredMethods.map { it.name }
            clazz.declaredFields
                .filter { it.isModelField() }
                .forEach { field: CtField ->
                    if (!methods.contains("realmGet\$${field.name}")) {
                        clazz.addMethod(CtNewMethod.getter("realmGet\$${field.name}", field))
                    }
                    if (!methods.contains("realmSet\$${field.name}")) {
                        clazz.addMethod(CtNewMethod.setter("realmSet\$${field.name}", field))
                    }
            }
        }

        /**
         * Modifies a class replacing field accesses with the appropriate Realm accessors.
         *
         * @param clazz The CtClass to modify
         * @param managedFields List of fields whose access should be replaced
         */
        @JvmStatic
        fun useRealmAccessors(
            classPool: ClassPool,
            clazz: CtClass,
            managedFields: List<CtField>? = null
        ) {
            val methodBehaviours = clazz.declaredBehaviors
                .filterIsInstance<CtMethod>()
                .filterNot { it.name.startsWith("realmGet$") }
                .filterNot { it.name.startsWith("realmSet$") }

            val constructorBehaviours = clazz.declaredBehaviors
                .filterIsInstance<CtConstructor>()

            (methodBehaviours + constructorBehaviours)
                .forEach { behavior ->
                    logger.debug("    Behavior: ${behavior.name}")
                    if (managedFields != null) {
                        behavior.instrument(
                            FieldAccessToAccessorConverterUsingList(
                                managedFields = managedFields,
                                ctClass = clazz,
                                behaviour = behavior
                            )
                        )
                    } else {
                        behavior.instrument(
                            FieldAccessToAccessorConverterUsingClassPool(
                                classPool = classPool,
                                ctClass = clazz,
                                behaviour = behavior
                            )
                        )
                    }
                }
        }

        /**
         * Modifies a class adding its RealmProxy interface.
         *
         * @param clazz The CtClass to modify
         * @param classPool the Javassist class pool
         */
        @JvmStatic
        fun addRealmProxyInterface(clazz: CtClass, classPool: ClassPool) {
            val proxyInterface: CtClass = classPool.get("io.realm.${clazz.getName().replace(".", "_")}RealmProxyInterface")
            clazz.addInterface(proxyInterface)
        }

        fun callInjectObjectContextFromConstructors(clazz: CtClass) {
            clazz.constructors.forEach {
                it.insertBeforeBody("if ($0 instanceof io.realm.internal.RealmObjectProxy) {" +
                        " ((io.realm.internal.RealmObjectProxy) $0).realm\$injectObjectContext();" +
                        " }")
            }
        }


        /**
         * Adds a method to indicate that Realm transformer has been applied.
         *
         * @param clazz The CtClass to modify.
         */
        fun overrideTransformedMarker(clazz: CtClass) {
            logger.debug("  Realm: Marking as transformed ${clazz.simpleName}")
            try {
                clazz.getDeclaredMethod("transformerApplied")
            } catch (ignored: NotFoundException) {
                clazz.addMethod(CtNewMethod.make(Modifier.PUBLIC, CtClass.booleanType, "transformerApplied",
                        arrayOf(), arrayOf(), "{return true;}", clazz))
            }
        }

    }

    /**
     * This class goes through all the field access behaviours of a class and replaces field accesses with
     * the appropriate accessor.
     */
    private class FieldAccessToAccessorConverterUsingList(val managedFields: List<CtField>,
                                                          val ctClass: CtClass,
                                                          val behaviour: CtBehavior) : ExprEditor() {

        @Throws(CannotCompileException::class)
        override fun edit(fieldAccess: FieldAccess) {
            logger.debug("      Field being accessed: ${fieldAccess.className}.${fieldAccess.fieldName}")
            managedFields.find {
                fieldAccess.className.equals(it.declaringClass.name) && fieldAccess.fieldName.equals(it.name)
            }?.run {
                logger.debug("        Realm: Manipulating ${ctClass.simpleName}.${behaviour.name}(): ${fieldAccess.fieldName}")
                logger.debug("        Methods: ${ctClass.declaredMethods}")
                val fieldName: String = fieldAccess.fieldName
                if (fieldAccess.isReader) {
                    fieldAccess.replace("\$_ = $0.realmGet\$$fieldName();")
                } else if (fieldAccess.isWriter) {
                    fieldAccess.replace("\$0.realmSet\$$fieldName(\$1);")
                }
            }
        }
    }

    /**
     * This class goes through all the field access behaviours of a class and replaces field accesses with
     * the appropriate accessor.
     */
    private class FieldAccessToAccessorConverterUsingClassPool(val classPool: ClassPool,
                                                               val ctClass: CtClass,
                                                               val behaviour: CtBehavior) : ExprEditor() {

        val realmObjectProxyInterface: CtClass = classPool.get("io.realm.internal.RealmObjectProxy")

        private fun FieldAccess.classOrNull(): CtClass? = try { classPool.get(className) } catch (e: NotFoundException) { null }

        @Throws(CannotCompileException::class)
        override fun edit(fieldAccess: FieldAccess) {
            logger.debug("      Field being accessed: ${fieldAccess.className}.${fieldAccess.fieldName}")

            fieldAccess.classOrNull()?.let { fieldAccessCtClass ->
                if (fieldAccessCtClass.isRealmModelClass() && fieldAccess.field.isModelField()) {
                    logger.debug("        Realm: Manipulating ${ctClass.simpleName}.${behaviour.name}(): ${fieldAccess.fieldName}")
                    logger.debug("        Methods: ${ctClass.declaredMethods}")

                    // make sure accessors are added, otherwise javassist will fail with
                    // javassist.CannotCompileException: [source error] realmGet$id() not found in 'foo.Model'
                    addRealmAccessors(fieldAccessCtClass)

                    val fieldName: String = fieldAccess . fieldName
                    if (fieldAccess.isReader) {
                        fieldAccess.replace("\$_ = \$0.realmGet\$$fieldName();")
                    } else if (fieldAccess.isWriter) {
                        fieldAccess.replace("\$0.realmSet\$$fieldName(\$1);")
                    }
                }
            }
        }

        fun CtClass.isRealmModelClass(): Boolean =
            name != "io.realm.RealmObject" &&
                    (hasAnnotation(RealmClass::class.java) || hasRealmClassAnnotation(superclass)) &&
                    safeSubtypeOf(realmObjectProxyInterface)
    }
}

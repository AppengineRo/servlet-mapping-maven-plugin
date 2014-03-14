package ro.adma;


import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.AttributeInfo;
import javassist.bytecode.ClassFile;
import javassist.bytecode.annotation.*;
import org.reflections.scanners.AbstractScanner;

public class AnnotationScanner extends AbstractScanner {

    public void scan(final Object cls) {
        if (cls instanceof ClassFile) {
            ClassFile classFile = (ClassFile) cls;
            AttributeInfo attribute = classFile.getAttribute(AnnotationsAttribute.visibleTag);
            if (attribute instanceof AnnotationsAttribute) {
                AnnotationsAttribute annotationAttribute = (AnnotationsAttribute) attribute;
                for (int i = 0; i < annotationAttribute.getAnnotations().length; i++) {
                    Annotation annotation = annotationAttribute.getAnnotations()[i];
                    MemberValue memberValue = annotation.getMemberValue("value");
                    getMemberValue(classFile.getName(), annotation.getTypeName(), memberValue);
                }
            } else {
                System.out.println("Nu este AnnotationsAttribute: " + attribute.getClass().getName());
            }
        } else {
            System.out.println("Nu este ClassFile: " + cls.getClass().getName());
        }
    }
    private void getMemberValue(String className, String typeName, MemberValue memberValue){
        if(memberValue instanceof StringMemberValue){
            getStore().put(className+"|"+typeName, String.valueOf(((StringMemberValue) memberValue).getValue()));
        } else if(memberValue instanceof ClassMemberValue) {
            ClassMemberValue classMemberValue = ((ClassMemberValue) memberValue);
            getStore().put(className+"|"+typeName, String.valueOf(classMemberValue.getValue()));
        } else if(memberValue instanceof BooleanMemberValue){
            boolean value = ((BooleanMemberValue) memberValue).getValue();
            getStore().put(className+"|"+typeName, String.valueOf(value));
        } else if(memberValue instanceof ArrayMemberValue) {
            ArrayMemberValue classMemberValue = ((ArrayMemberValue) memberValue);
            MemberValue[] value = classMemberValue.getValue();
            for(MemberValue val:value){
               getMemberValue(className, typeName, val);
            }
        } else if(memberValue instanceof EnumMemberValue){
            EnumMemberValue classMemberValue = ((EnumMemberValue) memberValue);
            getStore().put(className+"|"+typeName, classMemberValue.getValue());
        } else {
            System.out.print(memberValue.getClass().getName());
        }
    }
}

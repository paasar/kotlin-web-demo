/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

package org.jetbrains.kotlin.j2k;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import org.jetbrains.webdemo.ErrorWriter;
import org.jetbrains.webdemo.ResponseUtils;
import org.jetbrains.webdemo.session.SessionInfo;
import org.json.JSONArray;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class JavaToKotlinConverterForWebDemo {
    private final SessionInfo info;

    public JavaToKotlinConverterForWebDemo(SessionInfo info) {
        this.info = info;
    }

    public String getResult(String code, Project project) {
        JSONArray result = new JSONArray();
        Map<String, String> map = new HashMap<String, String>();
        try {
            String resultFormConverter;
            try {
                JavaToKotlinConverter converter = new JavaToKotlinConverter(
                        project,
                        ConverterSettings.defaultSettings,
                        EmptyReferenceSearcher.INSTANCE$,
                        EmptyResolverForConverter.INSTANCE$,
                        null);
                PsiFile javaFile = PsiFileFactory.getInstance(project).createFileFromText("test.java", JavaLanguage.INSTANCE, code);
                JavaToKotlinConverter.InputElement inputElement = new JavaToKotlinConverter.InputElement(javaFile, null);
                resultFormConverter = new JavaToKotlinTranslator().prettify(
                        converter.elementsToKotlin(Collections.singletonList(inputElement), new EmptyProgressIndicator())
                                .getResults().iterator().next().getText());
            } catch (Exception e) {
                return ResponseUtils.getErrorInJson("EXCEPTION: " + e.getMessage());
            }
            if (resultFormConverter.isEmpty()) {
                return ResponseUtils.getErrorInJson("EXCEPTION: generated code is empty.");
            }
            map.put("text", resultFormConverter);
        } catch (Throwable e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                    SessionInfo.TypeOfRequest.CONVERT_TO_KOTLIN.name(), info.getOriginUrl(), code);
            return ResponseUtils.getErrorInJson(e.getMessage());
        }
        finally {
//            Initializer.reinitializeJavaEnvironment();
        }

        result.put(map);
        return result.toString();
    }

}

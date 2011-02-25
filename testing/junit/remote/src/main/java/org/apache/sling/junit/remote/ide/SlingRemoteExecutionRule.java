/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.junit.remote.ide;

import java.io.ObjectInputStream;

import org.apache.http.HttpEntity;
import org.apache.sling.junit.remote.httpclient.RemoteTestHttpClient;
import org.apache.sling.testing.tools.http.RequestExecutor;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlingRemoteExecutionRule implements MethodRule {
   private static final Logger log = 
       LoggerFactory.getLogger(SlingRemoteExecutionRule.class);

   /** Name of the system property that activates remote test execution */
   public static final String SLING_REMOTE_TEST_URL = "sling.remote.test.url";
   
   public Statement apply(final Statement base, final FrameworkMethod method, Object target) {
       return new Statement() {
           @Override
           public void evaluate() throws Throwable {
               if (tryRemoteEvaluation(method)) {
                   return;
               }
               base.evaluate();
           }
       };
   }

   /**
    * Execute test remotely if the corresponding system property is set
    * 
    * @return <code>true</code> if the method was executed remotely and passed.
    *         If the test was <b>not</b> executed remotely then
    *         <code>false</code> is returned to indicate that test should be
    *         executed locally
    */
   private boolean tryRemoteEvaluation(FrameworkMethod method) throws Throwable {
       String remoteUrl = System.getProperty(SLING_REMOTE_TEST_URL);
       if(remoteUrl != null) {
           remoteUrl = remoteUrl.trim();
           if(remoteUrl.length() > 0) {
               invokeRemote(remoteUrl, method);
               return true;
           }
       }
       return false;
   }

   private void invokeRemote(String remoteUrl, FrameworkMethod method) throws Throwable {
       final String testClassesSelector = method.getMethod().getDeclaringClass().getName();
       final String methodName = method.getMethod().getName();
       
       final RemoteTestHttpClient testHttpClient = new RemoteTestHttpClient(remoteUrl, false);
       final RequestExecutor executor = testHttpClient.runTests(
               testClassesSelector, methodName, "serialized"
       );
       log.debug("Ran test {} method {} at URL {}",
               new Object[] { testClassesSelector, methodName, remoteUrl });
       
       final HttpEntity entity = executor.getResponse().getEntity();
       if (entity != null) {
           try {
               final Object o = new ObjectInputStream(entity.getContent()).readObject();
               if( !(o instanceof ExecutionResult) ) {
                   throw new IllegalStateException("Expected an ExecutionResult, got a " + o.getClass().getName());
               }
               final ExecutionResult result = (ExecutionResult)o;
               if (result.isFailure()) {
                   throw result.getException();
               }
           } finally {
               entity.consumeContent();
           }
       }
   }
}
/*
   Copyright 2018 Booz Allen Hamilton

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package org.boozallen.plugins.jte.config


import org.boozallen.plugins.jte.console.TemplateLogger
import org.boozallen.plugins.jte.utils.RunUtils
import org.codehaus.groovy.runtime.InvokerHelper
import jenkins.model.Jenkins

/*
  stores the aggregated & immutable pipeline configuration. 
*/
class PipelineConfig implements Serializable{
    TemplateConfigObject configObject = null

    TemplateConfigObject getConfig(){
      return configObject ?: new TemplateConfigObject() 
    }

    void join(TemplateConfigObject child){
      /*
        If this is the first call to join, then there is no pre-existing
        configuration to merge.  set the current pipeline configuration to 
        the child and return 
      */
      if(!configObject){
        configObject = child 
        printJoin(child, child, new TemplateConfigObject(config: [:], merge: [], override: []))
        return 
      }

      def pipeline_config
      def argCopy = TemplateConfigDsl.parse(TemplateConfigDsl.serialize(child))
      def prevCopy = TemplateConfigDsl.parse(TemplateConfigDsl.serialize(configObject))

      /*
        start out by wiping out any configurations that were
        already defined by the previous configuration.. bc governance
      */ 
      pipeline_config = child.config + configObject.config

      /*
        if the current pipeline configuration allows children configurations 
        to perform overrides for any block, then check the incoming pipeline 
        configuration being joined to see if that block has been modified. 

        If it has, overwrite the block with the child's. 
      */
      configObject.override.each{ key ->
        if (get_prop(child.config, key) != null){
          clear_prop(pipeline_config, key)
          if(get_prop(pipeline_config, key) instanceof Map){
            get_prop(pipeline_config, key) << get_prop(child.config, key)
          } else { 
            def newValue = get_prop(child.config, key)
            set_prop(pipeline_config, key, newValue)
          }
        }
      }

      /*
        if the current pipeline configuration allows children configurations 
        to perform merges for any block, then check the incoming pipeline 
        configuration being joined to see if that block has been modified. 

        If it has, add any new fields in the block but leave the already 
        defined ones as is. 
      */
      configObject.merge.each{ key ->
        if (get_prop(child.config, key) != null){
          get_prop(pipeline_config, key) << (get_prop(child.config, key) + get_prop(pipeline_config, key))
        }
      }

      // trim merge and overrides that don't apply 
      // see: https://github.com/jenkinsci/templating-engine-plugin/issues/48
      def r = getNested(pipeline_config)
      child.merge = child.merge.findAll{ m -> 
        r.keySet().collect{ it.startsWith(m) }.contains(true)
      }
      child.override = child.override.findAll{ o -> 
        r.keySet().collect{ it.startsWith(o) }.contains(true)
      }

      child.setConfig(pipeline_config)
      printJoin(child, argCopy, prevCopy)
      configObject = child

    }

    def get_prop(o, p){
      return p.tokenize('.').inject(o){ obj, prop ->       
        obj?."$prop"
      }   
    }

    void clear_prop(o, p){
      def last_token
      if (p.tokenize('.')){
        last_token = p.tokenize('.').last()
      } else if (InvokerHelper.getMetaClass(o).respondsTo(o, "clear", (Object[]) null)){
        o.clear()
      }
      p.tokenize('.').inject(o){ obj, prop ->    
        if (prop.equals(last_token) && InvokerHelper.getMetaClass(obj?."$prop").respondsTo(obj?."$prop", "clear", (Object[]) null)){
          obj?."$prop".clear()
        }
        obj?."$prop"
      }   
    }

    void set_prop(o, p, n){
      def last_token
      if (p.tokenize('.')){
        last_token = p.tokenize('.').last()
      } else if (InvokerHelper.getMetaClass(o).respondsTo(o, "clear", (Object[]) null)){
        o = n 
      }
      p.tokenize('.').inject(o){ obj, prop ->    
        if (prop.equals(last_token)){
          obj?."$prop" = n 
        }
        obj?."$prop"
      }   
    }

    def getNestedKeys(map, result = [], String keyPrefix = '') {
      map.each { key, value ->
        if (value instanceof Map) {
            getNestedKeys(value, result, "${keyPrefix}${key}.")
        } else {
            result << "${keyPrefix}${key}"
        }
      }
      return result
    }

    def getNested(map, resultKeys = [], String keyPrefix = '') {
        def ret = [:]
        map.each { key, value ->
          def pathKey = "${keyPrefix}${key}"

          if (value instanceof Map) {
            def nestedMap = getNested(value, resultKeys, "${pathKey}.")
            if( nestedMap.isEmpty()){// we are a leaf node and empty
              ret[pathKey] = value
              resultKeys << pathKey
            } else {// we are another map so add to existing map
                ret = ret + nestedMap
            }
          } else {
            ret[pathKey] = value
            resultKeys << pathKey
          }
        }

        return ret
    }

    void printJoin(TemplateConfigObject outcome, TemplateConfigObject incoming, TemplateConfigObject prev){
        // flatten each configuration for ease of delta analysis 
        def fOutcome = getNested(outcome.getConfig())
        def fIncoming = getNested(incoming.getConfig())
        def fPrevious = getNested(prev.getConfig())

        def output = ['Pipeline Configuration Modifications']

        // Determine Configurations Added
        def configurationsAdded = fOutcome.keySet() - fPrevious.keySet()
        if (configurationsAdded){
          output << "Configurations Added:" 
          configurationsAdded.each{ key -> 
            output << "- ${key} set to ${fOutcome[key]}"
          }
        }else{
          output << "Configurations Added: None" 
        }

        // Determine Configurations Deleted
        def configurationsDeleted = (fPrevious - fOutcome).keySet().findAll{ !(it in fOutcome.keySet()) }
        if (configurationsDeleted){
          output << "Configurations Deleted:" 
          configurationsDeleted.each{ key -> 
            output << "- ${key}"
          }
        }else{
          output << "Configurations Deleted: None" 
        }
        // Determine Configurations Changed 
        def configurationsChanged = (fOutcome - fPrevious).findAll{ it.getKey() in fPrevious.keySet() }
        if (configurationsChanged){
          output << "Configurations Changed:" 
          configurationsChanged.keySet().each{ key  -> 
            output << "- ${key} changed from ${fPrevious[key]} to ${fOutcome[key]}"
          }
        }else{
          output << "Configurations Changed: None" 
        }

        // Determine Configurations Duplicated
        def configurationsDuplicated = fPrevious.intersect(fIncoming)
        if (configurationsDuplicated){
          output << "Configurations Duplicated:" 
          configurationsDuplicated.keySet().each{ key -> 
            output << "- ${key}"
          }
        }else{
          output << "Configurations Duplicated: None" 
        }

        // Determine Configurations Ignored 
        def configurationsIgnored = (fIncoming - fOutcome).keySet()
        if (configurationsIgnored){
          output << "Configurations Ignored:" 
          configurationsIgnored.each{ key -> 
            output << "- ${key}"
          }
        }else{
          output << "Configurations Ignored: None" 
        }
        
        // Print Subsequent May Merge
        def subsequentMayMerge = outcome.merge 
        if(subsequentMayMerge){
          output << "Subsequent May Merge:"
          subsequentMayMerge.each{ key -> 
            output << "- ${key}" 
          }
        }else{
          output << "Subsequent May Merge: None" 
        }

        // Print Subsequent May Override 
        def subsequentMayOverride = outcome.override 
        if(subsequentMayOverride){
          output << "Subsequent May Override:"
          subsequentMayOverride.each{ key -> 
            output << "- ${key}" 
          }
        }else{
          output << "Subsequent May Override: None" 
        }

        TemplateLogger.print( output.join("\n"), [ initiallyHidden: true, trimLines: false ])
    }

}

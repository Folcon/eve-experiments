import {ENV, DEBUG, unpad, underline, coerceInput} from "./utils"
import * as runtime from "./runtime"
import {eve} from "./app"
import {repeat} from "./utils"
declare var uuid;

type SourceAttr = [string, string];
interface MapArgs {
  // value may be a [source, alias] pair to be applied or a constant
  [param:string]:SourceAttr|any
}

interface ListArgs {
  // value may be a [source, alias] pair to be applied or a constant
  [param: number]:SourceAttr|any
}

class ParseError extends Error {
  name: string = "Parse Error";

  constructor(public message:string, public line:string, public lineIx?:number, public charIx:number = 0, public length:number = line.length - charIx) {
    super(message);
  }
  toString() {
    return unpad(6) `
      ${this.name}: ${this.message}
      ${this.lineIx !== undefined ? `On line ${this.lineIx + 1}:${this.charIx}` : ""}
      ${this.line}
      ${underline(this.charIx, this.length)}
    `;
  }
}

function maybe<T>(val:Error|T):T {
  if(val instanceof Error) throw Error;
  return <T>val;
}

function readWhile(str:string, substring:string, startIx:number):string {
  let endIx = startIx;
  while(str[endIx] === substring) endIx++;
  return str.slice(startIx, endIx);
}

function readUntil(str:string, sentinel:string, startIx:number):string;
function readUntil(str:string, sentinel:string, startIx:number, unsatisfiedErr: Error):string|Error;
function readUntil(str:string, sentinel:string, startIx:number, unsatisfiedErr?: Error):any {
  let endIx = str.indexOf(sentinel, startIx);
  if(endIx === -1) {
    if(unsatisfiedErr) return unsatisfiedErr;
    return str.slice(startIx);
  }
  return str.slice(startIx, endIx);
}

function readUntilAny(str:string, sentinels:string[], startIx:number):string;
function readUntilAny(str:string, sentinels:string[], startIx:number, unsatisfiedErr: Error):string|Error;
function readUntilAny(str:string, sentinels:string[], startIx:number, unsatisfiedErr?: Error):any {
  let endIx = -1;
  for(let sentinel of sentinels) {
    let ix = str.indexOf(sentinel, startIx);
    if(ix === -1 || endIx !== -1 && ix > endIx) continue;
    endIx = ix;
  }
  if(endIx === -1) {
    if(unsatisfiedErr) return unsatisfiedErr;
    return str.slice(startIx);
  }
  return str.slice(startIx, endIx);
}

function getAlias(line:string, lineIx: number, charIx: number):[string, number] {
  let alias = uuid();
  let aliasIx = line.lastIndexOf("as [");
  if(aliasIx !== -1) {
    alias = readUntil(line, "]", aliasIx + 4, new ParseError(`Alias must terminate in a closing ']'`, line, lineIx, line.length));
    if(alias instanceof Error) return alias;
  } else aliasIx = undefined;
  return [alias, aliasIx];
}

function maybeCoerceAlias(maybeAlias:string):Error|any {
  if(maybeAlias[0] === "[") {
    if(maybeAlias[maybeAlias.length - 1] !== "]") return new Error("Attribute aliases must terminate in a closing ']'")
    let [source, attribute] = maybeAlias.slice(1, -1).split(",");
    if(!attribute) return new Error("Attribute aliases must contain a source, attribute pair");
    return [source.trim(), attribute.trim()];
  }
  return coerceInput(maybeAlias);
}

function getMapArgs(line:string, lineIx: number, charIx: number):[Error, number]|[MapArgs, number] {
  let args = {};
  if(line[charIx] === "{") {
    let endIx = line.indexOf("}", charIx);
    if(endIx === -1) return [new ParseError(`Args must terminate in a closing '}'`, line, lineIx, line.length), line.length];
    let syntaxErrorIx = line.indexOf("],");
    if(syntaxErrorIx !== -1) return [new ParseError(`Args are delimited by ';', not ','`, line, lineIx, syntaxErrorIx + 1, 0), charIx];
    for(let pair of line.slice(++charIx, endIx).split(";")) {
      let [key, val] = pair.split(":");
      if(key === undefined || val === undefined)
        return [new ParseError(`Args must be specified in key: value pairs`, line, lineIx, charIx, pair.length), charIx + pair.length + 1];

      let coerced = args[key.trim()] = maybeCoerceAlias(val.trim());
      if(coerced instanceof Error) {
        let valIx = charIx + pair.indexOf("[");
        return [new ParseError(coerced.message, line, lineIx, valIx), valIx];
      }

      charIx += pair.length + 1;
    }
    return [args, endIx + 1];
  }
  return [undefined, charIx];
}

function getListArgs(line:string, lineIx: number, charIx: number):[Error, number]|[ListArgs, number] {
  let args = [];
  if(line[charIx] === "{") {
    let endIx = line.indexOf("}", charIx);
    if(endIx === -1) return [new ParseError(`Args must terminate in a closing '}'`, line, lineIx, line.length), line.length];
    let syntaxErrorIx = line.indexOf("],");
    if(syntaxErrorIx !== -1) return [new ParseError(`Args are delimited by ';', not ','`, line, lineIx, syntaxErrorIx + 1, 0), charIx];
    for(let val of line.slice(++charIx, endIx).split(";")) {
      let coerced = maybeCoerceAlias(val.trim());
      if(coerced instanceof Error) {
        let valIx = charIx + val.indexOf("[");
        return [new ParseError(coerced.message, line, lineIx, valIx), valIx];
      }

      args.push(coerced);
      charIx += alert.length + 1;
    }
    return [args, charIx];
  }
  return [undefined, charIx];
}



//-----------------------------------------------------------------------------
// Plan DSL Parser
//-----------------------------------------------------------------------------
export interface PlanStep {
  id: string
  size?: number
  name?: string
  type?: string // find | <other>
  relatedTo?: PlanStep
}
interface PlanFind extends PlanStep { entity?: string }
interface PlanGather extends PlanStep { collection?: string }
interface PlanLookup extends PlanStep { deselect?: boolean, attribute: string }
interface PlanIntersect extends PlanStep { deselect?: boolean, collection: string, entity?: string }
interface PlanFilterByEntity extends PlanStep { deselect?: boolean, entity: string }
interface PlanFilter extends PlanStep { func: string, args: MapArgs }
interface PlanCalculate extends PlanStep { func: string, args: MapArgs }
interface PlanAggregate extends PlanStep { aggregate: string, args: MapArgs }
interface PlanGroup extends PlanStep { groups: any }
interface PlanSort extends PlanStep { sort: any }
interface PlanLimit extends PlanStep { limit: any }

function getDeselect(line, lineIx, charIx):[boolean, number] {
  let deselect = false;
  if(line[charIx] === "!") {
    deselect = true;
    charIx++;
    while(line[charIx] === " ") charIx++;
  }
  return [deselect, charIx];
}

let parsePlanStep:{[step: string]: (line: string, lineIx: number, charIx: number, related?: PlanStep) => Error|PlanStep} = {
  ["#"]() { // Comment noop
    return;
  },
  // Sources
  find(line, lineIx, charIx) {
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);
    let entity = line.slice(charIx, aliasIx).trim();
    if(!entity)
      return new ParseError(`Find step must specify a valid entity id`, line, lineIx, charIx);
    let step:PlanFind = {type: "find", id: alias, entity};
    return step;
  },
  gather(line, lineIx, charIx, relatedTo) {
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);
    let collection = line.slice(charIx, aliasIx).trim();
    if(!collection)
      return new ParseError(`Gather step must specify a valid collection id`, line, lineIx, charIx);
    let step:PlanGather = {type: "gather", id: alias, collection, relatedTo};
    return step;
  },

  // Joins
  lookup(line, lineIx, charIx, relatedTo) {
    if(!relatedTo) return new ParseError(`Lookup step must be a child of a root`, line, lineIx, charIx);
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);
    let deselect;
    [deselect, charIx] = getDeselect(line, lineIx, charIx);
    let attribute = line.slice(charIx, aliasIx).trim();
    if(!attribute)
      return new ParseError(`Lookup step must specify a valid attribute id.`, line, lineIx, charIx);
    let step:PlanLookup = {type: "lookup", id: alias, name: alias, attribute, deselect, relatedTo};
    return step;
  },
  intersect(line, lineIx, charIx, relatedTo) {
    if(!relatedTo) return new ParseError(`Lookup step must be a child of a root`, line, lineIx, charIx);
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);
    let deselect;
    [deselect, charIx] = getDeselect(line, lineIx, charIx);
    let collection = line.slice(charIx, aliasIx).trim();

    if(!collection)
      return new ParseError(`Intersect step must specify a valid collection id`, line, lineIx, charIx);
    let step:PlanIntersect = {type: "intersect", id: alias, collection, deselect, relatedTo};
    return step;
  },
  filterByEntity(line, lineIx, charIx, relatedTo) {
    if(!relatedTo) return new ParseError(`Lookup step must be a child of a root`, line, lineIx, charIx);
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);
    let deselect;
    [deselect, charIx] = getDeselect(line, lineIx, charIx);
    let entity = line.slice(charIx, aliasIx).trim();
    if(!entity)
      return new ParseError(`Intersect step must specify a valid entity id`, line, lineIx, charIx, entity.length);
    let step:PlanFilterByEntity = {type: "filter by entity", id: alias, entity, deselect, relatedTo};
    return step;
  },

  // Calculations
  filter(line, lineIx, charIx) {
    // filter positive
    // filter >; a: 7, b: [person age]
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);
    let lastIx = charIx;
    let filter = readUntil(line, "{", charIx); // @NOTE: Need to remove alias
    charIx += filter.length;
    filter = filter.trim();
    if(!filter)
      return new ParseError(`Filter step must specify a valid filter fn`, line, lineIx, lastIx);

    let args;
    [args, charIx] = getMapArgs(line, lineIx, charIx);
    if(args instanceof Error) return args;
    if(line.length > charIx) return new ParseError(`Filter step contains extraneous text`, line, lineIx, charIx);

    let step:PlanFilter = {type: "filter", id: alias, func: filter, args};
    return step;
  },
  calculate(line, lineIx, charIx) {
    // filter positive
    // filter >; a: 7, b: [person age]
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);
    let lastIx = charIx;
    let filter = readUntil(line, "{", charIx); // @NOTE: Need to remove alias
    charIx += filter.length;
    filter = filter.trim();
    if(!filter)
      return new ParseError(`Calculate step must specify a valid calculate fn`, line, lineIx, lastIx);

    let args;
    [args, charIx] = getMapArgs(line, lineIx, charIx);
    if(args instanceof Error) return args;

    let step:PlanFilter = {type: "calculate", id: alias, func: filter, args};
    return step;
  }
};

export function parsePlan(str:string):PlanStep[] {
  let plan:PlanStep[] = [];
  let errors = [];
  let lineIx = 0;
  let lines = str.split("\n")
  let stack:{indent: number, step: PlanStep}[] = [];
  for(let line of lines) {
    let charIx = 0;
    while(line[charIx] === " ") charIx++;
    let indent = charIx;
    if(line[charIx] === undefined)  continue;
    let related;
    for(let stackIx = stack.length - 1; stackIx >= 0; stackIx--) {
      if(indent > stack[stackIx].indent) {
        related = stack[stackIx].step;
        break;
      } else stack.pop();
    }
    let keyword = readUntil(line, " ", charIx);
    charIx += keyword.length;
    let step:Error|PlanStep;
    if(parsePlanStep[keyword]) step = parsePlanStep[keyword](line, lineIx, charIx, related);
    else step = new ParseError(`Keyword '${keyword}' is not a valid plan step, ignoring`, line, lineIx, charIx - keyword.length, keyword.length);

    if(step && step["args"]) {
      let args = step["args"];
      for(let arg in args) {
        if(args[arg] instanceof Array) {
          let [source] = args[arg];
          let valid = false;
          for(let step of plan) {
            if(step.id === source) {
              valid = true;
              break;
            }
          }
          if(!valid) {
            step = new ParseError(`Alias source '${source}' does not exist in plan`, line, lineIx, line.indexOf(`[${source},`) + 1, source.length);
          }
        }
      }
    }

    if(step instanceof Error) errors.push(step);
    else if(step) {
      plan.push(<PlanStep>step);
      stack.push({indent, step: <PlanStep>step});
    }

    lineIx++;
  }
  if(errors.length) {
    for(let err of errors) {
      console.error(err);
    }
  }
  return plan;
}

//-----------------------------------------------------------------------------
// Query DSL Parser
//-----------------------------------------------------------------------------
export interface QueryStep { type: string, id?: string }
interface QuerySelect extends QueryStep { view: string, join?: MapArgs }
interface QueryCalculate extends QueryStep { func: string, args: MapArgs }
interface QueryOrdinal extends QueryStep {}
interface QueryGroup extends QueryStep { groups: ListArgs }
interface QuerySort extends QueryStep { sorts: ListArgs }
interface QueryLimit extends QueryStep { limit: ListArgs }
interface QueryProject extends QueryStep { mapping: ListArgs }

let parseQueryStep:{[step: string]: (line: string, lineIx: number, charIx: number) => Error|QueryStep} = {
  ["#"]() { // Comment noop
    return;
  },
  select(line: string, lineIx: number, charIx: number) {
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);

    let lastIx = charIx;
    let viewRaw = readUntil(line, "{", charIx).slice(0, aliasIx ? aliasIx - charIx: undefined);
    charIx += viewRaw.length;
    let view = viewRaw.trim();
    if(!view)
      return new ParseError(`Select step must specify a valid view id`, line, lineIx, lastIx, viewRaw.length);

    let join;
    [join, charIx] = getMapArgs(line, lineIx, charIx);
    if(join instanceof Error) return join;

    let step:QuerySelect = {type: "select", id: alias, view, join};
    return step;
  },
  deselect(line: string, lineIx: number, charIx: number) {
    let step = parseQueryStep["select"](line, lineIx, charIx);
    if(step instanceof Error) return step;
    (<QueryStep>step).type = "deselect";
    return step;
  },
  calculate(line: string, lineIx: number, charIx: number) {
    while(line[charIx] === " ") charIx++;
    let [alias, aliasIx] = getAlias(line, lineIx, charIx);
    let lastIx = charIx;
    let funcRaw = readUntil(line, "{", charIx).slice(0, aliasIx ? aliasIx - charIx: undefined);
    charIx += funcRaw.length;
    let func = funcRaw.trim();

    if(!func)
      return new ParseError(`Calculate step must specify a valid function id`, line, lineIx, lastIx, funcRaw.length);

    let args;
    [args, charIx] = getMapArgs(line, lineIx, charIx);
    if(args instanceof Error) return args;

    let step:QueryCalculate = {type: "calculate", id: alias, func, args};
    return step;
  },
  aggregate(line: string, lineIx: number, charIx: number) {
    let step = parseQueryStep["calculate"](line, lineIx, charIx);
    if(step instanceof Error) return step;
    (<QueryStep>step).type = "aggregate";
    return step;
  },
  ordinal(line: string, lineIx: number, charIx: number) {
    let step:QueryOrdinal = {type: "ordinal"};
    return step;
  },
  group(line: string, lineIx: number, charIx: number) {
    while(line[charIx] === " ") charIx++;

    let groups;
    [groups, charIx] = getListArgs(line, lineIx, charIx);
    if(groups instanceof Error) return groups;

    let step:QueryGroup = {type: "group", groups}
    return step;
  },
  sort(line: string, lineIx: number, charIx: number) {
    while(line[charIx] === " ") charIx++;

    let sorts;
    [sorts, charIx] = getListArgs(line, lineIx, charIx);
    if(sorts instanceof Error) return sorts;

    let step:QuerySort = {type: "sort",  sorts}
    return step;
  },
  limit(line: string, lineIx: number, charIx: number) {
    while(line[charIx] === " ") charIx++;
    let args;
    [args, charIx] = getMapArgs(line, lineIx, charIx);
    if(args instanceof Error) return args;
    for(let key of Object.keys(args)) {
      if(key !== "results" && key !== "perGroup") return new ParseError(`Limit may only apply perGroup or to results`, line, lineIx, charIx);
    }

    let step:QueryLimit = {type: "limit", limit: args};
    return step;
  },
  project(line: string, lineIx: number, charIx: number) {
    while(line[charIx] === " ") charIx++;
    let args;
    [args, charIx] = getMapArgs(line, lineIx, charIx);
    if(args instanceof Error) return args;

    let step:QueryProject = {type: "project", mapping: args};
    return step;
  }
};

export function parseQuery(str:string):QueryStep[] {
  let plan:QueryStep[] = [];
  let errors = [];
  let lineIx = 0;
  let lines = str.split("\n")
  for(let line of lines) {
    let charIx = 0;
    while(line[charIx] === " ") charIx++;
    if(line[charIx] === undefined)  continue;
    let keyword = readUntil(line, " ", charIx);
    charIx += keyword.length;
    let step:Error|QueryStep;
    if(parseQueryStep[keyword]) step = parseQueryStep[keyword](line, lineIx, charIx);
    else step = new ParseError(`Keyword '${keyword}' is not a valid query step, ignoring`, line, lineIx, charIx - keyword.length, keyword.length);

    if(step && step["args"]) {
      let args = step["args"];
      for(let arg in args) {
        if(args[arg] instanceof Array) {
          let [source] = args[arg];
          let valid = false;
          for(let step of plan) {
            if(step.id === source) {
              valid = true;
              break;
            }
          }
          if(!valid) {
            step = new ParseError(`Alias source '${source}' does not exist in query`, line, lineIx, line.indexOf(`[${source},`) + 1, source.length);
          }
        }
      }
    }

    if(step instanceof Error) errors.push(step);
    else if(step) plan.push(<QueryStep>step);

    lineIx++;
  }
  if(errors.length) {
    // @FIXME: Return errors instead of logging them.
    for(let err of errors) {
      console.error(err.toString());
    }
  }
  return plan;
}

//-----------------------------------------------------------------------------
// UI DSL Parser
//-----------------------------------------------------------------------------
export interface UIElem {
  id?: string
  children?: UIElem[]
  embedded?: MapArgs // Undefined or the restricted scope of the embedded child.
  binding?: string
  bindingKind?: string
  attributes?: MapArgs
  events?: {[event:string]: MapArgs}
}

export function parseUI(str:string):UIElem {
  let root:UIElem = {};
  let errors = [];
  let lineIx = 0;
  let lines = str.split("\n");
  let stack:{indent: number, elem: UIElem}[] = [{indent: -2, elem: root}];
  // @FIXME: Chunk into element chunks instead of lines to enable in-argument continuation.
  for(let line of lines) {
    let charIx = 0;
    while(line[charIx] === " ") charIx++;
    let indent = charIx;
    if(line[charIx] === undefined)  continue;
    let parent:UIElem;
    for(let stackIx = stack.length - 1; stackIx >= 0; stackIx--) {
      if(indent > stack[stackIx].indent) {
        parent = stack[stackIx].elem;
        break;
      } else stack.pop();
    }
    let keyword = readUntil(line, " ", charIx);
    charIx += keyword.length;

    if(keyword[0] === "~" || keyword[0] === "%") { // Handle binding
      charIx -= keyword.length - 1;
      let kind = keyword[0] === "~" ? "plan" : "query";
      if(!parent.binding) {
        parent.binding = line.slice(charIx);
        parent.bindingKind = kind;
      } else if(kind === parent.bindingKind) parent.binding += "\n" + line.slice(charIx);
      else {
        errors.push(new ParseError(`UI must be bound to a single type of query.`, line, lineIx));
        continue;
      }
      charIx = line.length;

    } else if(keyword[0] === "@") { // Handle event
      charIx -= keyword.length - 1;
      let err;
      while(line[charIx] === " ") charIx++;
      let lastIx = charIx;
      let eventRaw = readUntil(line, "{", charIx);
      charIx += eventRaw.length;
      let event = eventRaw.trim();

      if(!event) err = new ParseError(`UI event must specify a valid event name`, line, lineIx, lastIx, eventRaw.length);
      let state;
      [state, charIx] = getMapArgs(line, lineIx, charIx);
      if(state instanceof Error && !err) err = state;
      if(err) {
        errors.push(err);
        lineIx++;
        continue;
      }

      if(!parent.events) parent.events = {};
      parent.events[event] = state;

    } else if(keyword[0] === ">") { // Handle embed
      charIx -= keyword.length - 1;
      let err;
      while(line[charIx] === " ") charIx++;
      let lastIx = charIx;
      let embedIdRaw = readUntil(line, "{", charIx);
      charIx += embedIdRaw.length;
      let embedId = embedIdRaw.trim();

      if(!embedId) err = new ParseError(`UI embed must specify a valid element id`, line, lineIx, lastIx, embedIdRaw.length);
      let scope;
      [scope = {}, charIx] = getMapArgs(line, lineIx, charIx);
      if(scope instanceof Error && !err) err = scope;
      if(err) {
        errors.push(err);
        lineIx++;
        continue;
      }

      let elem = {embedded: scope, id: embedId};
      if(!parent.children) parent.children = [];
      parent.children.push(elem);
      stack.push({indent, elem});

    } else { // Handle element
      let err;
      if(!keyword) err = new ParseError(`UI element must specify a valid tag name`, line, lineIx, charIx, 0);
      while(line[charIx] === " ") charIx++;
      let classesRaw = readUntil(line, "{", charIx);
      charIx += classesRaw.length;
      let classes = classesRaw.trim();

      let attributes;
      [attributes = {}, charIx] = getMapArgs(line, lineIx, charIx);
      if(attributes instanceof Error && !err) err = attributes;
      if(err) {
        errors.push(err);
        lineIx++;
        continue;
      }
      attributes["t"] = keyword;
      if(classes) attributes["c"] = classes;
      let elem:UIElem = {id: attributes["id"], attributes};
      if(!parent.children) parent.children = [];
      parent.children.push(elem);
      stack.push({indent, elem});
    }

    lineIx++;
  }

  if(errors.length) {
    for(let err of errors) {
      console.error(err);
    }
  }
  return root;
}


//-----------------------------------------------------------------------------
// Eve DSL Parser
//-----------------------------------------------------------------------------
enum TOKEN_TYPE { EXPR, IDENTIFIER, KEYWORD, STRING, LITERAL };
export class Token {
  static TYPE = TOKEN_TYPE;
  static identifier(value:string, lineIx?: number, charIx?: number) {
    return new Token(Token.TYPE.IDENTIFIER, value, lineIx, charIx);
  }
  static keyword(value:string, lineIx?: number, charIx?: number) {
    return new Token(Token.TYPE.KEYWORD, value, lineIx, charIx);
  }
  static string(value:string, lineIx?: number, charIx?: number) {
    return new Token(Token.TYPE.STRING, value, lineIx, charIx);
  }
  static literal(value:any, lineIx?: number, charIx?: number) {
    return new Token(Token.TYPE.LITERAL, value, lineIx, charIx);
  }

  constructor(public type?: TOKEN_TYPE, public value?: any, public lineIx?: number, public charIx?: number) {}
  toString() {
    if(this.type === Token.TYPE.KEYWORD) return `:${this.value}`;
    else if(this.type === Token.TYPE.STRING) return `"${this.value}"`;
    else return this.value.toString();
  }
}

export class Sexpr {
  static list(value:(Token|Sexpr)[] = [], lineIx?: number, charIx?: number, syntax?: boolean) {
    value = value.slice();
    value.unshift(Token.identifier("list", lineIx, charIx ? charIx + 1 : undefined));
    return new Sexpr(value, lineIx, charIx, syntax ? "list" : undefined);
  }
  static hash(value:(Token|Sexpr)[] = [], lineIx?: number, charIx?: number, syntax?: boolean) {
    value = value.slice();
    value.unshift(Token.identifier("hash", lineIx, charIx ? charIx + 1 : undefined));
    return new Sexpr(value, lineIx, charIx, syntax ? "hash" : undefined);
  }
  static asSexprs(values:(Token|Sexpr)[]):Sexpr[] {
    for(let raw of values) {
     if(!(raw instanceof Sexpr)) throw new ParseError(`All top level entries must be expressions`, undefined, raw.lineIx, raw.charIx);
      else {
        let op = raw.operator;
        if(op.type !== Token.TYPE.IDENTIFIER)
          throw new ParseError(`All expressions must begin with an identifier`, undefined, raw.lineIx, raw.charIx);
      }
    }
    return <Sexpr[]>values;
  }

  public type = Token.TYPE.EXPR;
  public value:(Token|Sexpr)[];

  constructor(val?: (Token|Sexpr)[], public lineIx?: number, public charIx?: number, public syntax = "expr") {
    if(val) this.value = val.slice();
  }
  toString() {
    let content = this.value && this.value.map((token) => token.toString()).join(" ");
    let argsContent = this.value && this.arguments.map((token) => token.toString()).join(" ");
    if(this.syntax === "hash") return `{${argsContent}}`;
    else if(this.syntax === "list") return `[${argsContent}]`;
    else return `(${content})`;
  }

  push(val:Token|Sexpr) {
    this.value = this.value || [];
    return this.value.push(val);
  }
  nth(n, val?:Token|Sexpr) {
    if(val) {
      this.value = this.value || [];
      return this.value[n] = val;
    }
    return this.value && this.value[n];
  }
  get operator() {
    return this.value && this.value[0];
  }
  set operator(op: Token|Sexpr) {
    this.value = this.value || [];
    this.value[0] = op;
  }
  get arguments() {
    return this.value && this.value.slice(1);
  }
  set arguments(args: (Token|Sexpr)[]) {
    this.value = this.value || [];
    this.value.length = 1;
    this.value.push.apply(this.value, args);
  }
  get length() {
    return this.value && this.value.length;
  }
}

const TOKEN_TO_TYPE = {
  "(": "expr",
  ")": "expr",
  "[": "list",
  "]": "list",
  "{": "hash",
  "}": "hash"
};

export function readSexprs(text:string):Sexpr {
  let root = Sexpr.list();
  let token:Token;
  let sexpr:Sexpr = root;
  let sexprs:Sexpr[] = [root];

  let lines = text.split("\n");
  let lineIx = 0;
  let mode:string;
  for(let line of lines) {
    let line = lines[lineIx];
    let charIx = 0;

    if(mode === "string") token.value += "\n";

    while(charIx < line.length) {
      if(mode === "string") {
        if(line[charIx] === "\"" && line[charIx - 1] !== "\\") {
          sexpr.push(token);
          token = mode = undefined;
          charIx++;

        } else token.value += line[charIx++];

        continue;
      }

      let padding = readWhile(line, " ", charIx);
      charIx += padding.length;
      if(padding.length) {
        if(token) sexpr.push(token);
        token = undefined;
      }
      if(charIx >= line.length) continue;

      if(line[charIx] === ";") {
        charIx = line.length;

      } else if(line[charIx] === "\"") {
        if(!sexpr.length) throw new ParseError(`Literal must be an argument in a sexpr.`, line, lineIx, charIx);
        mode = "string";
        token = Token.string("", lineIx, charIx);
        charIx++;

      } else if(line[charIx] === ":") {
        if(!sexpr.length) throw new ParseError(`Literal must be an argument in a sexpr.`, line, lineIx, charIx);
        let keyword = readUntilAny(line, [" ", ")", "]", "}"], ++charIx);
        sexpr.push(Token.keyword(keyword, lineIx, charIx - 1));
        charIx += keyword.length;

      } else if(line[charIx] === "(" || line[charIx] === "[" || line[charIx] === "{") {
        if(token) throw new ParseError(`Sexpr arguments must be space separated.`, line, lineIx, charIx);
        let type = TOKEN_TO_TYPE[line[charIx]];
        if(type === "hash") sexpr = Sexpr.hash(undefined, lineIx, charIx);
        else if(type === "list") sexpr = Sexpr.list(undefined, lineIx, charIx);
        else sexpr = new Sexpr(undefined, lineIx, charIx);
        sexpr.syntax = type;
        sexprs.push(sexpr);
        charIx++;

      } else if(line[charIx] === ")" || line[charIx] === "]" || line[charIx] === "}") {
        let child = sexprs.pop();
        let type = TOKEN_TO_TYPE[line[charIx]];
        if(child.syntax !== type) throw new ParseError(`Must terminate ${child.syntax} before terminating ${type}`, line, lineIx, charIx);
        sexpr = sexprs[sexprs.length - 1];
        if(!sexpr) throw new ParseError(`Too many closing parens`, line, lineIx, charIx);
        sexpr.push(child);
        charIx++;

      } else {
        let literal = readUntilAny(line, [" ", ")", "]", "}"], charIx);
        let length = literal.length;
        literal = coerceInput(literal);
        let type = typeof literal === "string" ? "identifier" : "literal";
        if(!sexpr.length && type !== "identifier") throw new ParseError(`Expr must begin with identifier.`, line, lineIx, charIx);
        if(type === "identifier") {
          let dotIx = literal.indexOf(".");
          if(dotIx !== -1) {
            let child:Sexpr = new Sexpr([
              Token.identifier("get", lineIx, charIx + 1),
              Token.identifier(literal.slice(0, dotIx), lineIx, charIx + 3),
              Token.string(literal.slice(dotIx + 1), lineIx, charIx + 5 + dotIx)
            ], lineIx, charIx);
            sexpr.push(child);

          } else sexpr.push(Token.identifier(literal, lineIx, charIx));

        } else sexpr.push(Token.literal(literal, lineIx, charIx));
        charIx += length;
      }
    }
    lineIx++;
  }
  if(token) throw new ParseError(`Unterminated ${token.type} token`, lines[lineIx - 1], lineIx - 1);
  let lastIx = lines.length - 1;
  if(sexprs.length > 1) throw new ParseError(`Too few closing parens`, lines[lastIx], lastIx, lines[lastIx].length);

  return root;
}

export function macroexpandDSL(sexpr:Sexpr):Sexpr {
  // @TODO: Implement me.
  let op = sexpr.operator;
  if(op.value === "eav") {
    throw new Error("@TODO: Implement me!");

  } else if(op.value === "one-of") {
    // (one-of (query ...body) (query ...body) ...) =>
    // (union
    //   (def q1 (query ...body1))
    //   (def q2 (query (negate q1) ...body2)))
    throw new Error("@TODO: Implement me!");

  } else if(op.value === "negate") {
    if(sexpr.length > 2) throw new ParseError(`Negate only take a single body`, undefined, sexpr.lineIx, sexpr.charIx);
    let select = macroexpandDSL(Sexpr.asSexprs(sexpr.arguments)[0]);
    select.push(Token.keyword("$$negated"));
    select.push(Token.literal(true));
    return select;

  } else if(["hash", "list", "get", "def", "query", "union", "select", "project!"].indexOf(op.value) === -1) {
    // (foo-bar :a 5) => (select "foo bar" :a 5)
    let source = op;
    source.type = Token.TYPE.STRING;
    source.value = source.value.replace(/([^\s])-([^\s])/g, "$1 $2");
    let args = sexpr.arguments;
    args.unshift(source);
    sexpr.arguments = args;
    sexpr.operator = Token.identifier("select");
  }
  return sexpr;
}
enum VALUE { NULL, SCALAR, SET, VIEW };
type Artifacts = {[query:string]: runtime.Query|runtime.Union};
type Variable = {name: string, type: VALUE, static?: boolean, value?: any};
type VariableContext = Variable[];

export function parseDSL(text:string):Artifacts {
  let artifacts:Artifacts = {};
  let lines = text.split("\n");
  let root = readSexprs(text);

  for(let raw of Sexpr.asSexprs(root.arguments)) parseDSLSexpr(raw, artifacts);
  return artifacts;
}
const primitives = {
  "+": "calculate",
  "-": "calculate",
  "*": "calculate",
  "/": "calculate",
  "=": "filter",
  "<": "filter",
  "<=": "filter",
  "sum": "aggregate",
  "count": "aggregate",
  "max": "aggregate"
  //@TODO: Finish me.
};

function parseDSLSexpr(raw:Sexpr, artifacts:Artifacts, context?:VariableContext, query?:runtime.Query) {
  let sexpr = macroexpandDSL(raw);
  let op = sexpr.operator;
  if(op.type !== Token.TYPE.IDENTIFIER)
    throw new ParseError(`Evaluated sexpr must begin with an identifier ('${op}' is a ${Token.TYPE[op.type]})`, "", raw.lineIx, raw.charIx);

  if(op.value === "query") {
    let neueContext:VariableContext = [];
    let queryId = uuid();
    let neue = new runtime.Query(eve, queryId);
    if(DEBUG.instrumentQuery) instrumentQuery(neue, DEBUG.instrumentQuery);
    artifacts[queryId] = neue;
    for(let raw of Sexpr.asSexprs(sexpr.arguments)) parseDSLSexpr(raw, artifacts, neueContext, neue);

    let projectionMap = {};
    for(let variable of neueContext) projectionMap[variable.name] = variable.value;
    if(Object.keys(projectionMap).length) neue.project(projectionMap);

    // Join subquery to parent.
    if(query) {
      // @TODO: Macroize queries to make them negateable? Or add special handling for $$negated passthrough on query.
      let select = new Sexpr([Token.identifier("select"), Token.string(queryId)], raw.lineIx, raw.charIx);
      let groups = [];
      for(let variable of neueContext) {
        select.push(Token.keyword(variable.name));
        select.push(Token.identifier(variable.name));

        for(let parentVar of context) {
          if(parentVar.name === variable.name) groups.push(variable.value);
        }
      }
      if(groups.length) neue.group(groups);
      parseDSLSexpr(select, artifacts, context, query);
    }

    return queryId;
  }

  if(!query) throw new ParseError(`Non-query sexprs must be contained within a query`, "", raw.lineIx, raw.charIx);

  if(op.value === "select") {
    let selectId = uuid();
    let args = parseArguments(sexpr, ["$$view"]);
    let {$$view, $$negated} = args;
    let view = resolveTokenValue("view", $$view, context, VALUE.SCALAR);
    if(view === undefined) throw new ParseError("Must specify a view to be selected", "", raw.lineIx, raw.charIx);

    let join = {};
    for(let arg in args) {
      let value = args[arg];
      if(arg === "$$view" || arg === "$$negated") continue;
      if(value.type !== Token.TYPE.IDENTIFIER) {
        join[arg] = args[arg].value;
        continue;
      }

      let variable = getDSLVariable(value.value, context);
      if(variable) join[arg] = variable.value;
      else if($$negated && $$negated.value)
        throw new ParseError(`Cannot bind field in negated select to undefined variable '${value.value}'`, "", raw.lineIx, raw.charIx);
      else context.push({name: value.value, type: VALUE.SCALAR, value: [selectId, arg]});
    }

    if(primitives[view]) {
      if(primitives[view] === "aggregate") query.aggregate(view, join, selectId);
      else query.calculate(view, join, selectId);
    } else if($$negated) query.deselect(view, join);
    else query.select(view, join, selectId);
    return;
  }

  if(op.value === "project!") {
    let args = parseArguments(sexpr, ["$$view"]);
    let {$$view, $$negated} = args;
    let view = resolveTokenValue("view", $$view, context, VALUE.SCALAR);
    if(view === undefined) throw new ParseError("Must specify a view to project into", "", raw.lineIx, raw.charIx);
    let union = new runtime.Union(eve, view);
    artifacts[uuid()] = union; // @NOTE: This is super weird. This should probably just be an array of stuff.
    if(DEBUG.instrumentQuery) instrumentQuery(union, DEBUG.instrumentQuery);
    let projectionMap = {};
    for(let arg in args) {
      let value = args[arg];
      if(arg === "$$view" || arg === "$$negated") continue;
      if(value.type !== Token.TYPE.IDENTIFIER) {
        projectionMap[arg] = args[arg].value;
        continue;
      }

      let variable = getDSLVariable(value.value, context);
      if(variable) {
        if(variable.static) projectionMap[arg] = variable.value;
        else projectionMap[arg] = [variable.name];
      } else throw new ParseError(`Cannot bind projected field to undefined variable '${value.value}'`, "", raw.lineIx, raw.charIx);
    }
    // if($$negated.value) union.ununion(queryId, projectionMap);
    if($$negated && $$negated.value)
      throw new ParseError(`Union projections may not be negated in the current runtime`, "", raw.lineIx, raw.charIx);
    else union.union(query.name, projectionMap);
    return view;
  }

  throw new ParseError(`Unknown DSL operator '${op.value}'`, "", raw.lineIx, raw.charIx);
}

function resolveTokenValue(name:string, token:Token, context:VariableContext, type?:VALUE) {
  if(!token) return;
  if(token.type === Token.TYPE.IDENTIFIER) {
    let variable = getDSLVariable(token.value, context, VALUE.SCALAR);
    if(!variable) throw new Error(`Cannot bind ${name} to undefined variable '${token.value}'`);
    if(!variable.static) throw new Error(`Cannot bind ${name} to dynamic variable '${token.value}'`);
    return variable.value;
  }
  return token.value;
}

function getDSLVariable(name:string, context:VariableContext, type?:VALUE):Variable {
  for(let variable of context) {
    if(variable.name === name) {
      if(variable.static === false) throw new Error(`Cannot statically look up dynamic variable '${name}'`);
      if(type !== undefined && variable.type !== type)
        throw new Error(`Expected variable '${name}' to have type '${type}', but instead has type '${variable.type}'`);
      return variable;
    }
  }
}

export function parseArguments(root:Sexpr, defaults?:string[]):{[keyword:string]: Token} {
  let args:any = {};
  let defaultIx = 0;
  let keyword;
  let kwarg = false;
  for(let raw of root.arguments) {
    if(raw.type === Token.TYPE.KEYWORD) {
      if(keyword) throw new Error(`Keywords may not be values '${raw}'`);
      else keyword = raw.value;
    } else if(keyword) {
      if(args[keyword] === undefined) {
        args[keyword] = raw;
      } else {
        if(!(args[keyword] instanceof Array)) args[keyword] = [args[keyword]];
        args[keyword].push(raw);
      }
      keyword = undefined;
      defaultIx = defaults.length;
      kwarg = true;
    } else if(defaults && defaultIx < defaults.length) {
      args[defaults[defaultIx++]] = raw;
    } else {
      if(kwarg) throw new Error("Cannot specify an arg after a kwarg");
      else if(defaultIx) throw new Error(`Too many args, expected: ${defaults.length}, got: ${defaultIx + 1}`);
      else throw new Error("Cannot specify an arg without default keys specified");
    }
  }

  return args;
}

declare var exports;
if(ENV === "browser") window["parser"] = exports;

export function instrumentQuery(q:any, instrument?:Function|boolean) {
  let instrumentation:Function = <Function>instrument;
  if(!instrument || instrument === true) instrumentation = (fn, args) => console.log("*", fn, ":", args);
  let keys = [];
  for(let key in q) keys.push(key);
  keys.forEach((fn) => {
    if(!q.constructor.prototype.hasOwnProperty(fn) || typeof q[fn] !== "function") return;
    var old = q[fn];
    q[fn] = function() {
      instrumentation(fn, arguments);
      return old.apply(this, arguments);
    }
  });
  return q;
}

export function applyAsDiffs(views:{[id:string]:runtime.Query|runtime.Union}) {
  for(let id in views) eve.applyDiff(views[id].changeset(eve));
  console.log("Applied diffs for:");
  for(let id in views) console.log("  * ", views[id] instanceof runtime.Query ? "Query" : "Union", views[id].name);
}
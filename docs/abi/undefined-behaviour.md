# Undefined behaviour

Since Millfork is only a middle-level programming language and attempts to eschew runtime checks in favour of performance, 
there are many situation when the program may not behave as expected. 
In the following list, "undefined value" means an arbitrary value that cannot be relied upon, 
and "undefined behaviour" means arbitrary and unpredictable behaviour that may lead to anything, 
even up to hardware damage. 

* array overruns: indexing past the end of an array leads to undefined behaviour 

* stray pointers: indexing a pointer that doesn't point to a valid object or indexing it past the end of the pointed object leads to undefined behaviour

* reading uninitialized variables: will return undefined values

* reading variables used by return dispatch statements but not assigned a value: will return undefined values

* returning a value from a function by return dispatch to a function of different return type: will return undefined values

* passing an index out of range for a return dispatch statement

* stack overflow: exhausting the hardware stack due to excess recursion, excess function calls or excess stack-allocated variables

* on ROM-based platforms: writing to arrays

* on ROM-based platforms: using global variables with an initial value

* violating the [safe assembly rules](../lang/assembly.md)

* violating the [safe reentrancy rules](../lang/reentrancy.md)

The above list is not exhaustive.
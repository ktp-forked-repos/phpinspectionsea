<?php

class CasesHolder {
    private $property;

    public function method() {
        return [
            function() { return $this->property; },
            static function() { return null; },
            static function() { return null; },
        ];
    }
}